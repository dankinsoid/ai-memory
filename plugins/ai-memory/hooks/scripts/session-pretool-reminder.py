#!/usr/bin/env python3
# @ai-generated(solo)
"""PreToolUse hook: fallback reminder to call memory_session.

UserPromptSubmit already reminds on the first prompt, but if the agent ignores
it and starts doing work (Read, Edit, Bash, Agent, etc.), this hook catches
that and reminds again.

Logic:
- On memory_session tool call → clear the "needs init" flag, approve silently.
- On any other non-memory tool call → if flag exists, remind agent to call
  memory_session first (if the session topic is already clear).
- MCP ai-memory tools are excluded — they are auto-approved by a separate
  matcher and should not trigger reminders.

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
    tool_name = data.get("tool_name", "")

    if not session_id:
        print(json.dumps({"decision": "approve"}))
        return

    state_key = f"session-needs-init-{session_id}"

    try:
        from lib.db import get_state, delete_state
    except Exception:
        print(json.dumps({"decision": "approve"}))
        return

    # If this IS the memory_session call — clear the flag, done
    if "memory_session" in tool_name:
        delete_state(state_key)
        print(json.dumps({"decision": "approve"}))
        return

    # For any other tool: check if reminder is pending
    flag = get_state(state_key)
    if not flag:
        print(json.dumps({"decision": "approve"}))
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
    print(json.dumps({"decision": "approve", "message": msg}))


if __name__ == "__main__":
    main()
