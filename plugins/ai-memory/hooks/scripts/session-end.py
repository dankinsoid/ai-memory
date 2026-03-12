#!/usr/bin/env python3
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
