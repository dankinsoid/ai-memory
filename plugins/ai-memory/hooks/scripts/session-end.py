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
# Env-var toggles (set any to disable):
#   AI_MEMORY_DISABLED=1     — master switch (all hooks)
#   AI_MEMORY_NO_WRITE=1     — disable all writes
#   AI_MEMORY_NO_SESSIONS=1  — disable session-specific features

import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


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
    if any(os.environ.get(v) for v in ("AI_MEMORY_DISABLED", "AI_MEMORY_NO_WRITE", "AI_MEMORY_NO_SESSIONS")):
        sys.exit(0)

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

    # Matcher should filter, but double-check
    if hook_reason != "clear":
        sys.exit(0)

    if not session_id:
        sys.exit(0)

    project = derive_project(cwd)
    if project:
        cache_file = state_dir / f"prev-session-{project}.json"
        payload = {
            "session_id": session_id,
            "project": project,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }
        cache_file.write_text(json.dumps(payload))

        with open(log_file, "a") as f:
            f.write(
                f"{datetime.now(timezone.utc).isoformat()} | "
                f"WROTE cache {cache_file} | {payload!r}\n"
            )


if __name__ == "__main__":
    main()
