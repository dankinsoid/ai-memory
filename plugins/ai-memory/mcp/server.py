#!/usr/bin/env python3
# @ai-generated(solo)
from __future__ import annotations
"""MCP stdio server for ai-memory.

Implements JSON-RPC 2.0 over stdin/stdout with newline-delimited messages.
Reads one JSON object per line from stdin, writes one JSON object per line
to stdout.  Logs to stderr only.

Tools exposed:
  memory_session      — upsert session .md file
  memory_remember     — save a rule/preference .md file
  memory_search       — search all memory files by tags/text/date (truncated)
  memory_read         — read full content of a memory file by [[ref]]
  memory_explore_tags — list all tags (or fuzzy-resolve approximate tag names)

Usage (stdio MCP):
  python3 server.py
"""

import json
import re
import sys
import traceback
from pathlib import Path

# Allow importing from the shared lib/ package regardless of cwd
sys.path.insert(0, str(Path(__file__).parent.parent))

from lib import storage  # noqa: E402 — must come after sys.path patch

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

PROTOCOL_VERSION = "2024-11-05"
SERVER_INFO = {"name": "ai-memory", "version": "1.0.0"}

TOOLS = [
    {
        "name": "memory_session",
        "description": "Upsert a session summary",
        "inputSchema": {
            "type": "object",
            "properties": {
                "session_id": {"type": "string"},
                "project": {"type": "string"},
                "title": {"type": "string", "description": "3-8 words, English"},
                "summary": {
                    "type": "string",
                    "description": "1-3 sentences: full arc (replaces previous). No file/function names.",
                },
                "tags": {
                    "type": "array",
                    "items": {"type": "string"}
                },
                "compact": {
                    "type": "string",
                    "description": "Detailed compact notes for /load recovery",
                },
            },
            "required": ["session_id", "title", "summary"],
        },
    },
    {
        "name": "memory_remember",
        "description": "Save a rule or fact to long-term memory",
        "inputSchema": {
            "type": "object",
            "properties": {
                "content": {
                        "type": "string",
                        "description": (
                            "Free-form markdown. Short entries: 1-4 sentences. "
                            "Longer entries: lead with a brief summary paragraph, "
                            "then add detail below."
                        ),
                    },
                "tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": (
                        "Three tiers: "
                        "(1) context — 'universal' or 'project/<name>' or 'lang/<name>'; "
                        "add 'rule' if rule/preference; "
                        "(2) aspect tags; "
                        "(3) specific topic/technology tags"
                    ),
                },
                "title": {
                    "type": "string",
                    "description": "Kebab-case filename stem, e.g. 'always-run-regression-test-before-fixing-bug'",
                },
            },
            "required": ["content", "tags", "title"],
        },
    },
    {
        "name": "memory_search",
        "description": "Search memory by semantic query and/or tag/date filters. Covers facts, rules, sessions.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Semantic search (natural language)"},
                "tags": {"type": "array", "items": {"type": "string"}, "description": "All must match"},
                "any_tags": {"type": "array", "items": {"type": "string"}, "description": "At least one must match"},
                "exclude_tags": {"type": "array", "items": {"type": "string"}},
                "since": {"type": "string", "description": "YYYY-MM-DD"},
                "until": {"type": "string", "description": "YYYY-MM-DD"},
                "limit": {"type": "integer", "description": "Default 20"},
            },
        },
    },
    {
        "name": "memory_read",
        "description": "Read full content of a memory file by its [[ref]] wikilink.",
        "inputSchema": {
            "type": "object",
            "properties": {"ref": {"type": "string"}},
            "required": ["ref"],
        },
    },
    {
        "name": "memory_explore_tags",
        "description": "List all tags with file counts. If 'tags' given, fuzzy-resolve approximate names to existing tags.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Approximate tag names to resolve (optional)",
                },
            },
        },
    },
]


# ---------------------------------------------------------------------------
# Request handlers
# ---------------------------------------------------------------------------


def _handle_initialize(_params: dict) -> dict:
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "capabilities": {"tools": {}},
        "serverInfo": SERVER_INFO,
    }


def _handle_tools_list(_params: dict) -> dict:
    return {"tools": TOOLS}


def _truncate_to_first_paragraph(content: str) -> tuple[str, bool]:
    """Keep front matter + first paragraph of the body, drop the rest.

    A paragraph is a block of text separated by blank lines.
    If the first paragraph is a ``## `` heading, the next paragraph
    (its content) is included too.

    Returns (truncated_content, was_truncated).
    """
    # Find end of front matter
    body_start = 0
    if content.startswith("---"):
        end_fm = content.find("\n---", 1)
        if end_fm != -1:
            body_start = end_fm + 4  # skip past closing ---\n

    body = content[body_start:].strip()
    if not body:
        return content, False

    paragraphs = re.split(r"\n\n+", body)

    # If first paragraph is just a heading, include the next one too
    take = 1
    if paragraphs[0].startswith("## "):
        take = 2

    if len(paragraphs) <= take:
        return content, False

    kept = "\n\n".join(paragraphs[:take])
    return content[:body_start] + "\n" + kept + "\n", True



def _first_content_line(content: str) -> str:
    """Return the first non-empty body line of a .md file, skipping front-matter.

    Front-matter is delimited by leading/trailing '---' lines.
    Returns empty string if no body content found.
    """
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


def _handle_tools_call(params: dict) -> dict:
    name = params.get("name")
    args: dict = params.get("arguments") or {}

    try:
        if name == "memory_session":
            auto_tags = ["session"]
            if args.get("project"):
                auto_tags.append(f"project/{args['project']}")
            session_tags: list[str] = list(dict.fromkeys(
                auto_tags + (args.get("tags") or [])
            ))
            path = storage.upsert_session(
                session_id=args["session_id"],
                project=args.get("project"),
                title=args["title"],
                summary=args["summary"],
                tags=session_tags,
                compact=args.get("compact"),
            )
            # Lazy-load rules relevant to the session's topic tags.
            # Scope/meta tags are already in context from SessionStart — skip them.
            _SCOPE_TAGS = {"universal", "session", "rule"}
            topic_tags = [
                t for t in session_tags
                if t not in _SCOPE_TAGS and not t.startswith("project/")
            ]
            rules_text = ""
            if topic_tags:
                # Load dedup state — tracks rule paths already returned this session.
                from lib.db import get_state, set_state
                dedup_key = f"{args['session_id']}-loaded-rules"
                already_loaded: set[str] = set()
                raw = get_state(dedup_key)
                if raw:
                    try:
                        already_loaded = set(json.loads(raw))
                    except Exception:
                        pass

                candidates = storage.search_facts(
                    any_tags=topic_tags,
                    exclude_tags=["session"],
                    sort_by="date",
                    limit=10,
                )
                # Only surface rules; skip already-shown ones
                rules = [
                    f for f in candidates
                    if ("rule" in f.get("tags", []))
                    and f.get("path", "") not in already_loaded
                ][:5]
                if rules:
                    lines = []
                    for r in rules:
                        tag_str = ", ".join(r.get("tags", []))
                        content_line = _first_content_line(r.get("content") or "")
                        ref = f"[[{Path(r.get('path', '')).stem}]]" if r.get("path") else ""
                        lines.append(f"- {ref} [{tag_str}] {content_line}")
                    rules_text = "\n\nRelevant rules for current session topics:\n" + "\n".join(lines)
                    # Persist newly shown rule paths
                    newly_loaded = already_loaded | {r["path"] for r in rules if r.get("path")}
                    set_state(dedup_key, json.dumps(list(newly_loaded)))
            return _text(f"ok{rules_text}")

        if name == "memory_remember":
            raw_tags: list[str] = args.get("tags") or []
            # Deduplicate tags via vector similarity when vectorization is on.
            # Without it there's no reliable fuzzy match, so tags are stored as-is.
            from lib.vector_store import tag_store
            if tag_store.enabled:
                resolved = storage.resolve_tags(raw_tags)
                # Preserve originals that had no match (new tags, scope tags, etc.)
                known = set(resolved)
                tags = resolved + [t for t in raw_tags if t not in known]
            else:
                tags = raw_tags
            rel_path = storage.remember(
                content_text=args["content"],
                tags=tags,
                title=args.get("title"),
            )
            stem = Path(rel_path).stem
            return _text(f"Saved → [[{stem}]]")

        if name == "memory_search":
            results = storage.search_facts(
                tags=args.get("tags"),
                any_tags=args.get("any_tags"),
                exclude_tags=args.get("exclude_tags"),
                query=args.get("query"),
                since=args.get("since"),
                until=args.get("until"),
                sort_by=args.get("sort_by", "date"),
                limit=args.get("limit", 20),
                offset=args.get("offset", 0),
            )
            # Return raw markdown with truncation — only first section shown,
            # use memory_read for full content.
            parts = []
            for r in results:
                ref = r.get("ref", "")
                content = r.get("content", "")
                truncated_content, was_truncated = _truncate_to_first_paragraph(content)
                header = f"## {ref}"
                if "score" in r:
                    header += f" (score: {r['score']})"
                if was_truncated:
                    header += " (truncated)"
                parts.append(f"{header}\n{truncated_content}")
            if not parts:
                return _text("No results found.")
            return _text("\n\n".join(parts))

        if name == "memory_read":
            stem = args["ref"].strip("[]")
            found = storage.find_file_by_stem(stem)
            if not found:
                return _error(f"Not found: [[{stem}]]")
            content = found.read_text(encoding="utf-8")
            return _text(f"## [[{stem}]]\n{content}")

        if name == "memory_explore_tags":
            resolve_input = args.get("tags")
            if resolve_input:
                # Fuzzy-resolve mode: normalize approximate tag names
                from lib.vector_store import tag_store
                if not tag_store.enabled:
                    return _text(json.dumps(
                        {"resolved": [], "note": "vectorization disabled"},
                        ensure_ascii=False,
                    ))
                resolved = storage.resolve_tags(resolve_input)
                return _text(json.dumps({"resolved": resolved}, ensure_ascii=False))
            # Default: list all tags with counts
            return _text(json.dumps(storage.explore_tags(), indent=2, ensure_ascii=False))

        return _error(f"Unknown tool: {name!r}")

    except KeyError as e:
        return _error(f"Missing required argument: {e}")
    except Exception:
        return _error(traceback.format_exc())


def _text(text: str) -> dict:
    """Successful tool result with text content."""
    return {"content": [{"type": "text", "text": text}]}


def _error(text: str) -> dict:
    """Error tool result."""
    return {"content": [{"type": "text", "text": text}], "isError": True}


from typing import Callable

_HANDLERS: dict[str, Callable[[dict], dict]] = {
    "initialize": _handle_initialize,
    "tools/list": _handle_tools_list,
    "tools/call": _handle_tools_call,
}


# ---------------------------------------------------------------------------
# Main loop
# ---------------------------------------------------------------------------


def main() -> None:
    """Read newline-delimited JSON-RPC 2.0 messages from stdin, write to stdout.

    Notifications (messages without 'id') are processed but produce no output.
    Unknown methods return a JSON-RPC method-not-found error.
    Parse errors are logged to stderr and the line is skipped.
    """
    for raw in sys.stdin:
        raw = raw.strip()
        if not raw:
            continue

        try:
            msg = json.loads(raw)
        except json.JSONDecodeError as e:
            print(f"[ai-memory] JSON parse error: {e}", file=sys.stderr, flush=True)
            continue

        msg_id = msg.get("id")
        method: str = msg.get("method", "")
        params: dict = msg.get("params") or {}

        # Notification — no response expected
        if msg_id is None:
            handler = _HANDLERS.get(method)
            if handler:
                try:
                    handler(params)
                except Exception:
                    pass
            continue

        handler = _HANDLERS.get(method)
        if handler is None:
            resp = {
                "jsonrpc": "2.0",
                "id": msg_id,
                "error": {"code": -32601, "message": f"Method not found: {method!r}"},
            }
        else:
            try:
                result = handler(params)
                resp = {"jsonrpc": "2.0", "id": msg_id, "result": result}
            except Exception as e:
                resp = {
                    "jsonrpc": "2.0",
                    "id": msg_id,
                    "error": {"code": -32603, "message": str(e)},
                }

        print(json.dumps(resp, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
