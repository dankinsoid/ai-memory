#!/usr/bin/env python3
# @ai-generated(solo)
"""UserPromptSubmit async hook: prefetch relevant rules via gpt-4o-mini.

Runs asynchronously — does not block the agent. Extracts topic tags and a
search query from the user's prompt (+ recent transcript context), searches
for matching rules, and caches the results in state DB for the PreToolUse
inject hook to pick up.

Flow:
  1. Read available tags (cached per session)
  2. Build context: user prompt + last assistant message from transcript
  3. Call gpt-4o-mini: extract relevant tags + English search query
  4. Search rules: by tags (structured) + by query (semantic, if available)
  5. Deduplicate against already-shown rules
  6. Cache results in `rules-prefetch-{session_id}`

Requires OPENAI_API_KEY. Silently exits when key is missing.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

# Maximum chars from transcript to include as context
_TRANSCRIPT_TAIL_CHARS = 2000
# Maximum rules to prefetch
_MAX_RULES = 5


def _get_transcript_context(transcript_path: str | None) -> str:
    """Extract last assistant message from transcript JSONL for context.

    Args:
        transcript_path: path to Claude Code transcript JSONL file

    Returns:
        Last assistant message text (truncated), or empty string.
    """
    if not transcript_path:
        return ""
    p = Path(transcript_path)
    if not p.exists():
        return ""
    try:
        lines = p.read_text().splitlines()
        # Walk backwards to find last assistant message
        for line in reversed(lines[-50:]):
            try:
                entry = json.loads(line)
                msg = entry.get("message", {})
                if msg.get("role") == "assistant":
                    content = msg.get("content", "")
                    if isinstance(content, list):
                        # Extract text blocks
                        texts = [
                            b.get("text", "")
                            for b in content
                            if isinstance(b, dict) and b.get("type") == "text"
                        ]
                        content = "\n".join(texts)
                    if content:
                        return content[-_TRANSCRIPT_TAIL_CHARS:]
            except Exception:
                continue
    except Exception:
        pass
    return ""


def _get_available_tags(session_id: str) -> list[str]:
    """Get available tags, caching per session.

    Args:
        session_id: current session UUID

    Returns:
        List of tag names from storage.
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
        # Cache for this session
        from lib.db import set_state
        set_state(cache_key, json.dumps(tags))
        return tags
    except Exception:
        return []


def _extract_topics(
    prompt: str,
    context: str,
    available_tags: list[str],
) -> dict | None:
    """Call gpt-4o-mini to extract relevant tags and search query.

    Args:
        prompt: user's message
        context: recent assistant context from transcript
        available_tags: list of available tag names

    Returns:
        {"tags": [...], "query": "..."} or None on failure.
    """
    from lib.llm import chat_json

    # Filter to non-session, non-project tags for the prompt —
    # project tags are too specific, session tags aren't useful for rules
    topic_tags = [
        t for t in available_tags
        if not t.startswith("project/") and t != "session" and t != "rule"
    ]

    system = (
        "You extract topic tags and a search query from a user's message to find "
        "applicable coding rules and conventions.\n\n"
        "Available tags (pick 0-3 that match the topic):\n"
        f"{', '.join(topic_tags)}\n\n"
        "Return JSON: {\"tags\": [\"tag1\", ...], \"query\": \"english search query for rules\"}\n"
        "- tags: only from the available list, relevant to the task topic\n"
        "- query: short English phrase describing what conventions/rules might apply\n"
        "- If the message is a greeting or unclear, return {\"tags\": [], \"query\": \"\"}"
    )

    user_msg = f"User message: {prompt}"
    if context:
        user_msg += f"\n\nRecent context:\n{context[:500]}"

    return chat_json(system, user_msg, timeout=8)


def _search_rules(tags: list[str], query: str) -> list[dict]:
    """Search for rules by tags and/or query, deduplicated.

    Args:
        tags: topic tags to filter by
        query: semantic search query string

    Returns:
        List of rule dicts with 'ref' and 'content' keys.
    """
    from lib import storage

    seen_refs: set[str] = set()
    results: list[dict] = []

    # Tag-based search (always works)
    if tags:
        tag_results = storage.search_facts(
            tags=["rule"],
            any_tags=tags,
            exclude_tags=["session"],
            limit=_MAX_RULES,
        )
        for r in tag_results:
            ref = r.get("ref", "")
            if ref not in seen_refs:
                seen_refs.add(ref)
                results.append(r)

    # Query-based search (requires embeddings)
    if query:
        query_results = storage.search_facts(
            tags=["rule"],
            exclude_tags=["session"],
            query=query,
            limit=_MAX_RULES,
        )
        for r in query_results:
            ref = r.get("ref", "")
            if ref not in seen_refs:
                seen_refs.add(ref)
                results.append(r)

    return results[:_MAX_RULES]


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
    prompt = data.get("prompt", "") or ""
    transcript = data.get("transcript_path")

    if not session_id or not prompt:
        return

    # Check if LLM is available
    from lib.llm import is_enabled
    if not is_enabled():
        return

    # Skip very short prompts (greetings, "да", "ок", etc.)
    if len(prompt.strip()) < 10:
        return

    # Get available tags
    available_tags = _get_available_tags(session_id)

    # Get transcript context
    context = _get_transcript_context(transcript)

    # Extract topics via gpt-4o-mini
    topics = _extract_topics(prompt, context, available_tags)
    if not topics:
        return

    tags = topics.get("tags") or []
    query = topics.get("query") or ""

    if not tags and not query:
        return

    # Search for matching rules
    rules = _search_rules(tags, query)
    if not rules:
        return

    # Load already-shown rules for dedup
    from lib.db import get_state
    shown_key = f"rules-shown-{session_id}"
    shown_raw = get_state(shown_key)
    shown_refs: set[str] = set()
    if shown_raw:
        try:
            shown_refs = set(json.loads(shown_raw))
        except Exception:
            pass

    # Filter out already-shown rules
    new_rules = [r for r in rules if r.get("ref", "") not in shown_refs]
    if not new_rules:
        return

    # Format for injection: compact one-line-per-rule format
    formatted = []
    for r in new_rules:
        ref = r.get("ref", "")
        first_line = _content_first_line(r.get("content", ""))
        formatted.append({"ref": ref, "line": f"- [rule] {first_line} {ref}"})

    # Cache in state DB for PreToolUse inject hook
    from lib.db import set_state
    set_state(
        f"rules-prefetch-{session_id}",
        json.dumps({"rules": formatted}),
    )


if __name__ == "__main__":
    main()
