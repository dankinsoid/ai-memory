#!/usr/bin/env python3
# @ai-generated(solo)
"""PreToolUse hook: auto-approve ai-memory MCP tools and track memory_search calls.

Sets `rules-searched-{session_id}` flag when agent calls memory_search with
rule-related tags, so the pretool reminder can stop nudging about rules.
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

    # Always approve ai-memory MCP tools
    print(json.dumps({"decision": "approve"}))

    # Track memory_search calls that include rule tags
    if not session_id:
        return
    if "memory_search" not in tool_name:
        return

    tool_input = data.get("tool_input", {})
    tags = tool_input.get("tags") or []
    any_tags = tool_input.get("any_tags") or []
    all_tags = tags + any_tags

    # Consider it a "rules search" if the agent searched with rule tag,
    # or any query-based search (which might find rules)
    has_rule_tag = "rule" in all_tags
    has_query = bool(tool_input.get("query"))

    if has_rule_tag or has_query:
        try:
            from lib.db import set_state
            set_state(f"rules-searched-{session_id}", "1")
        except Exception:
            pass


if __name__ == "__main__":
    main()
