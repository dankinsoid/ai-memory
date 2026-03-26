#!/usr/bin/env python3
from __future__ import annotations
# @ai-generated(solo)
# SessionEnd hook: caches current session info for continuation linking.
#
# Fires on: clear (only). Writes prev-session cache so that the next
# SessionStart (clear) can link the new session to this one.
#
# Cache file: ~/.claude/hooks/state/prev-session-{project}.json
#   {"session_id": "...", "project": "...", "timestamp": "..."}
#

import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

# Allow importing from plugin lib/ package
sys.path.insert(0, str(Path(__file__).parent.parent.parent))


def git_project_name(cwd: str) -> str | None:
    """Extract repo name from git remote URL.
    Returns last path component of origin URL (without .git), or None.
    """
    if not cwd:
        return None
    try:
        result = subprocess.run(
            ["git", "-C", cwd, "remote", "get-url", "origin"],
            capture_output=True, text=True
        )
        if result.returncode == 0:
            url = result.stdout.strip().rstrip("/")
            url = url.removesuffix(".git")
            return url.split("/")[-1].split(":")[-1]
    except Exception:
        pass
    return None


def derive_project(cwd: str) -> str | None:
    """Derive project name from git remote, falling back to directory name."""
    return git_project_name(cwd) or (cwd.rstrip("/").split("/")[-1] if cwd else None)


def main() -> None:
    data = json.loads(sys.stdin.read())
    session_id = data.get("session_id")
    hook_reason = data.get("reason")
    cwd = data.get("cwd", "")

    state_dir = Path.home() / ".claude" / "hooks" / "state"
    state_dir.mkdir(parents=True, exist_ok=True)
    log_file = state_dir / "session-end.log"

    # Debug log — always write so we see every invocation
    with open(log_file, "a") as f:
        f.write(
            f"{datetime.now(timezone.utc).isoformat()} | "
            f"reason={hook_reason!r} | session={session_id} | cwd={cwd}\n"
        )

    if not session_id:
        sys.exit(0)

    # Clean up loaded-rules dedup cache regardless of reason — keyed by session_id
    # so a new session always starts fresh, but we clean up promptly to avoid accumulation.
    try:
        from lib.db import delete_state
        delete_state(f"{session_id}-loaded-rules")
    except Exception:
        # Fallback: clean up legacy JSON file
        dedup_file = state_dir / f"{session_id}-loaded-rules.json"
        if dedup_file.exists():
            try:
                dedup_file.unlink()
            except Exception:
                pass

    # Final auto-digest on /clear — force update regardless of delta threshold
    if hook_reason == "clear":
        try:
            from lib.config import llm_cfg
            if llm_cfg.enabled:
                from lib.digest import (
                    DigestState, compute_digest,
                    deserialize_state, serialize_state,
                )
                from lib.db import get_state, set_state

                # Find transcript
                transcript_path = data.get("transcript_path")
                if not transcript_path:
                    projects_dir = Path.home() / ".claude" / "projects"
                    matches = list(projects_dir.glob(f"**/{session_id}.jsonl"))
                    transcript_path = str(matches[0]) if matches else None

                if transcript_path:
                    tp = Path(transcript_path)
                    if tp.exists():
                        entries = []
                        for line in tp.read_text(encoding="utf-8", errors="replace").splitlines():
                            try:
                                entries.append(json.loads(line))
                            except Exception:
                                pass

                        project = derive_project(cwd)

                        state_raw = get_state(f"digest-state-{session_id}")
                        state = deserialize_state(state_raw) if state_raw else DigestState(
                            last_byte_offset=0, last_digest=None, last_msg_count=0,
                            agent_compact=None, agent_compact_msg_count=0,
                        )
                        agent_compact_raw = get_state(f"agent-compact-{session_id}")
                        if agent_compact_raw and agent_compact_raw != state.agent_compact:
                            current_msgs = len([
                                e for e in entries
                                if e.get("type") in ("user", "assistant")
                                and not e.get("isMeta")
                            ])
                            state = DigestState(
                                last_byte_offset=state.last_byte_offset,
                                last_digest=state.last_digest,
                                last_msg_count=state.last_msg_count,
                                agent_compact=agent_compact_raw,
                                agent_compact_msg_count=current_msgs,
                            )

                        result = compute_digest(entries, state, project, force=True)
                        if result:
                            digest, new_state = result
                            from lib import storage
                            auto_tags = ["session"]
                            if project:
                                auto_tags.append(f"project/{project}")
                            auto_tags.extend(t for t in digest.tags if t not in auto_tags)
                            storage.upsert_session(
                                session_id=session_id,
                                project=project,
                                title=digest.title,
                                summary=digest.summary,
                                tags=auto_tags,
                                compact=digest.compact,
                            )
                            set_state(f"digest-state-{session_id}", serialize_state(new_state))
        except Exception:
            pass  # never block /clear

    # Matcher should filter, but double-check: prev-session cache only on /clear
    if hook_reason != "clear":
        sys.exit(0)

    project = derive_project(cwd)
    if project:
        payload = {
            "session_id": session_id,
            "project": project,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }
        try:
            from lib.db import set_state
            set_state(f"prev-session-{project}", json.dumps(payload))
        except Exception:
            # Fallback: write legacy JSON file
            cache_file = state_dir / f"prev-session-{project}.json"
            cache_file.write_text(json.dumps(payload))

        with open(log_file, "a") as f:
            f.write(
                f"{datetime.now(timezone.utc).isoformat()} | "
                f"WROTE prev-session-{project} | {payload!r}\n"
            )


if __name__ == "__main__":
    main()
