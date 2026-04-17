#!/usr/bin/env python3
# @ai-generated(guided)
"""SessionEnd async hook: final LLM digest on /clear.

Forces a digest update with remaining delta regardless of threshold,
so the session file has the most complete summary before the context
is cleared.

Runs async — does not block /clear. The synchronous session-end.py
handles continuation linking separately.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from lib import detect_agent  # noqa: E402
from lib.transcript import find_transcript, load_transcript  # noqa: E402


def git_project_name(cwd: str) -> str | None:
    """Extract repo name from git remote URL."""
    if not cwd:
        return None
    try:
        result = subprocess.run(
            ["git", "-C", cwd, "remote", "get-url", "origin"],
            capture_output=True, text=True,
        )
        if result.returncode == 0:
            url = result.stdout.strip().rstrip("/").removesuffix(".git")
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
    cwd = data.get("cwd", "")
    agent = detect_agent(data)

    if not session_id:
        sys.exit(0)

    try:
        from lib.config import llm_cfg
        if not llm_cfg.enabled:
            sys.exit(0)
    except Exception:
        sys.exit(0)

    try:
        from lib.digest import (
            DigestState, compute_digest,
            deserialize_state, serialize_state,
        )
        from lib.db import get_state, set_state

        # Find transcript
        transcript_path = data.get("transcript_path")
        tp = Path(transcript_path) if transcript_path else find_transcript(session_id, agent)
        if not tp or not tp.exists():
            sys.exit(0)

        entries = load_transcript(tp)

        if not entries:
            sys.exit(0)

        project = derive_project(cwd)

        state_raw = get_state(f"digest-state-{session_id}")
        state = deserialize_state(state_raw) if state_raw else DigestState(
            last_byte_offset=0, last_digest=None, last_msg_count=0,
            agent_compact=None, agent_compact_msg_count=0, facts=[],
        )

        # Check for agent compact
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
                facts=state.facts,
            )

        result = compute_digest(entries, state, project, force=True)
        if result:
            digest, new_state = result
            from lib import storage
            auto_tags = ["session"]
            if project:
                auto_tags.append(f"project/{project}")
            auto_tags.extend(t for t in digest.tags if t not in auto_tags)
            facts_for_storage = [
                (f.text, f.importance) for f in new_state.facts
            ] if new_state.facts else None
            storage.upsert_session(
                session_id=session_id,
                project=project,
                title=digest.title,
                summary=digest.summary,
                tags=auto_tags,
                compact=digest.compact,
                facts=facts_for_storage,
            )
            set_state(f"digest-state-{session_id}", serialize_state(new_state))
    except Exception:
        pass  # never crash on /clear


if __name__ == "__main__":
    main()
