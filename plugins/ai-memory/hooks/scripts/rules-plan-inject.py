#!/usr/bin/env python3
# @ai-generated(solo)
"""PostToolUse hook (ExitPlanMode): inject rules relevant to the plan.

After the agent finalizes a plan, this hook reads the plan content from the
tool result, calls gpt-4o-mini to extract topics, searches for matching
rules, and injects them — so the agent sees applicable conventions before
execution begins.

Sync — runs after ExitPlanMode, before the agent starts executing the plan.
Deduplicates against already-shown rules from prefetch or manual search.

Requires OPENAI_API_KEY. Silently exits when key is missing.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

_MAX_RULES = 5


def _get_available_tags(session_id: str) -> list[str]:
    """Get available tags from session cache or storage.

    Args:
        session_id: current session UUID

    Returns:
        List of tag name strings.
    """
    cache_key = f"tags-cache-{session_id}"
    try:
        from lib.db import get_state
        cached = get_state(cache_key)
        if cached:
            return json.loads(cached)
    except Exception:
        pass

    try:
        from lib import storage
        result = storage.explore_tags()
        tags = [t["name"] for t in result.get("tags", [])]
        from lib.db import set_state
        set_state(cache_key, json.dumps(tags))
        return tags
    except Exception:
        return []


def _content_first_line(content: str) -> str:
    """Extract the first non-front-matter non-empty line from content."""
    in_fm = False
    for i, line in enumerate(content.splitlines()):
        if i == 0 and line.strip() == "---":
            in_fm = True
            continue
        if in_fm:
            if line.strip() == "---":
                in_fm = False
            continue
        stripped = line.strip()
        if stripped:
            return stripped
    return ""


def main() -> None:
    data = json.loads(sys.stdin.read())
    session_id = data.get("session_id")
    tool_name = data.get("tool_name", "")

    if not session_id or tool_name != "ExitPlanMode":
        return

    from lib.llm import is_enabled
    if not is_enabled():
        return

    # Get plan content from tool result
    tool_result = data.get("tool_result", "")
    if isinstance(tool_result, dict):
        tool_result = tool_result.get("content", "")
    if isinstance(tool_result, list):
        # Extract text from content blocks
        texts = [
            b.get("text", "")
            for b in tool_result
            if isinstance(b, dict) and b.get("type") == "text"
        ]
        tool_result = "\n".join(texts)

    # Also try tool_input which may contain the plan
    tool_input = data.get("tool_input", {})
    if isinstance(tool_input, dict):
        plan_text = tool_input.get("plan", "") or tool_input.get("text", "")
        if plan_text and not tool_result:
            tool_result = plan_text

    if not tool_result or len(tool_result) < 20:
        return

    # Extract topics from plan via gpt-4o-mini
    available_tags = _get_available_tags(session_id)
    topic_tags = [
        t for t in available_tags
        if not t.startswith("project/") and t != "session" and t != "rule"
    ]

    from lib.llm import chat_json

    system = (
        "You extract topic tags and a search query from a development plan to find "
        "applicable coding rules and conventions.\n\n"
        "Available tags (pick 0-3 that match the plan's topics):\n"
        f"{', '.join(topic_tags)}\n\n"
        "Return JSON: {\"tags\": [\"tag1\", ...], \"query\": \"english search query for rules\"}\n"
        "- tags: only from the available list, relevant to the plan's work\n"
        "- query: short English phrase describing what conventions/rules might apply"
    )

    # Truncate plan to avoid token limits
    plan_excerpt = tool_result[:1500]
    topics = chat_json(system, f"Plan:\n{plan_excerpt}", timeout=8)
    if not topics:
        return

    tags = topics.get("tags") or []
    query = topics.get("query") or ""
    if not tags and not query:
        return

    # Search for rules
    from lib import storage

    seen_refs: set[str] = set()
    results: list[dict] = []

    if tags:
        for r in storage.search_facts(
            tags=["rule"], any_tags=tags, exclude_tags=["session"], limit=_MAX_RULES
        ):
            ref = r.get("ref", "")
            if ref not in seen_refs:
                seen_refs.add(ref)
                results.append(r)

    if query:
        for r in storage.search_facts(
            tags=["rule"], exclude_tags=["session"], query=query, limit=_MAX_RULES
        ):
            ref = r.get("ref", "")
            if ref not in seen_refs:
                seen_refs.add(ref)
                results.append(r)

    results = results[:_MAX_RULES]
    if not results:
        return

    # Dedup against already-shown rules
    from lib.db import get_state, set_state

    shown_raw = get_state(f"rules-shown-{session_id}")
    try:
        shown_refs = set(json.loads(shown_raw)) if shown_raw else set()
    except Exception:
        shown_refs = set()

    new_rules = [r for r in results if r.get("ref", "") not in shown_refs]
    if not new_rules:
        return

    # Inject
    lines = []
    new_refs = set()
    for r in new_rules:
        ref = r.get("ref", "")
        first_line = _content_first_line(r.get("content", ""))
        lines.append(f"- [rule] {first_line} {ref}")
        new_refs.add(ref)

    print("Applicable rules for this plan (follow as conventions):")
    print("\n".join(lines))

    # Update shown refs
    updated_shown = shown_refs | new_refs
    set_state(f"rules-shown-{session_id}", json.dumps(list(updated_shown)))


if __name__ == "__main__":
    main()
