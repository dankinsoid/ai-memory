#!/usr/bin/env python3
# @ai-generated(solo)
"""PreToolUse hook: reminders + auto-injected rules from prefetch cache.

Three checks (in priority order):

1. Session init — if agent hasn't called memory_session yet, remind.
2. Rules inject — if rules-prefetch cache has new rules, inject them as
   system-reminder lines. This is the "sync inject" counterpart to the
   async rules-prefetch.py hook.
3. Rules search reminder — if no prefetched rules and agent hasn't searched
   for rules yet, nudge to call memory_search.

Fires on work tools (Read, Edit, Write, Bash, Agent, etc.) and EnterPlanMode.
MCP ai-memory tools use a separate auto-approve matcher.

Flags:
  `session-needs-init-{session_id}` — set by session-reminder.py, cleared by MCP memory_session
  `rules-searched-{session_id}` — set by memory-tool-approve.py when memory_search is called
  `rules-prefetch-{session_id}` — set by rules-prefetch.py (async), contains cached rules
  `rules-shown-{session_id}` — refs already injected (dedup set, JSON array)
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
        return

    try:
        from lib.db import get_state, set_state, delete_state
    except Exception:
        return

    # 1. Session init reminder (highest priority)
    init_flag = get_state(f"session-needs-init-{session_id}")
    if init_flag:
        try:
            flag_data = json.loads(init_flag)
        except Exception:
            flag_data = {}

        project = flag_data.get("project", "")
        session_part = f', project: "{project}"' if project else ""

        print(
            f'You haven\'t registered this session yet. '
            f'If the session topic is already clear, call memory_session with '
            f'session_id: "{session_id}"{session_part}, title, summary, and tags '
            f'before continuing with other work.'
        )
        return

    # 2. Rules inject from prefetch cache
    prefetch_raw = get_state(f"rules-prefetch-{session_id}")
    if prefetch_raw:
        try:
            prefetch = json.loads(prefetch_raw)
            rules = prefetch.get("rules", [])
        except Exception:
            rules = []

        if rules:
            # Load already-shown refs for dedup
            shown_raw = get_state(f"rules-shown-{session_id}")
            try:
                shown_refs = set(json.loads(shown_raw)) if shown_raw else set()
            except Exception:
                shown_refs = set()

            # Filter to new rules only
            new_rules = [r for r in rules if r.get("ref", "") not in shown_refs]

            if new_rules:
                lines = [r["line"] for r in new_rules]
                print("Applicable rules from memory (follow as conventions):")
                print("\n".join(lines))

                # Update shown refs
                new_shown = shown_refs | {r["ref"] for r in new_rules}
                set_state(f"rules-shown-{session_id}", json.dumps(list(new_shown)))

        # Clear prefetch cache — consumed
        delete_state(f"rules-prefetch-{session_id}")
        return

    # 3. Rules search reminder — nudge if agent hasn't checked rules yet
    rules_flag = get_state(f"rules-searched-{session_id}")
    if not rules_flag:
        if tool_name == "EnterPlanMode":
            print(
                'Before planning, check memory for applicable rules and conventions: '
                'call memory_search(tags=["rule"]) with relevant topic tags or a query.'
            )
        else:
            print(
                'Reminder: check memory for applicable rules before making decisions — '
                'call memory_search(tags=["rule"]) if you haven\'t yet.'
            )


if __name__ == "__main__":
    main()
