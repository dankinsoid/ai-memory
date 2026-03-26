#!/usr/bin/env python3
# @ai-generated(solo)
"""PreToolUse hook: fallback reminder to call memory_session.

UserPromptSubmit already reminds on the first prompt, but if the agent ignores
it and starts doing work (Read, Edit, Bash, Agent, etc.), this hook catches
that and reminds again.

Fires on work tools only (Read, Edit, Write, Bash, Agent, Glob, Grep, etc.).
MCP ai-memory tools are auto-approved by a separate matcher and don't trigger
this hook. The flag is cleared by the MCP server when memory_session is called.

The flag `session-needs-init-{session_id}` is set by session-reminder.py on
the first prompt. It contains {"project": "..."} for the reminder message.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))


def main() -> None:
    data = json.loads(sys.stdin.read())
    session_id = data.get("session_id")

    if not session_id:
        return

    # When LLM auto-digest is on, the flag is never set — skip entirely.
    try:
        from lib.config import llm_cfg
        if llm_cfg.enabled:
            return
    except Exception:
        pass

    state_key = f"session-needs-init-{session_id}"

    try:
        from lib.db import get_state
    except Exception:
        return

    flag = get_state(state_key)
    if not flag:
        return

    try:
        flag_data = json.loads(flag)
    except Exception:
        flag_data = {}

    project = flag_data.get("project", "")
    session_part = f', project: "{project}"' if project else ""

    msg = (
        f'You haven\'t registered this session yet. '
        f'If the session topic is already clear, call memory_session with '
        f'session_id: "{session_id}"{session_part}, title, summary, and tags '
        f'before continuing with other work.'
    )
    print(msg)


if __name__ == "__main__":
    main()
