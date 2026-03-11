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
  memory_search       — search all memory files by tags/text/date
  memory_explore_tags — list all tags with file counts
  memory_resolve_tags — fuzzy-match approximate tag names to existing ones

Usage (stdio MCP):
  python3 server.py
"""

import json
import sys
import traceback
from pathlib import Path

# Allow importing sibling modules (tags.py, storage.py) regardless of cwd
sys.path.insert(0, str(Path(__file__).parent))

import storage  # noqa: E402 — must come after sys.path patch

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

PROTOCOL_VERSION = "2024-11-05"
SERVER_INFO = {"name": "ai-memory", "version": "1.0.0"}

TOOLS = [
    {
        "name": "memory_session",
        "description": (
            "Upsert a session .md file. Call at end of session or on /save "
            "to persist context across restarts. Each call replaces the "
            "previous content for the same session_id."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "session_id": {
                    "type": "string",
                    "description": "Unique session identifier (UUID or similar)",
                },
                "project": {
                    "type": "string",
                    "description": "Project name; routes to projects/<name>/sessions/",
                },
                "title": {
                    "type": "string",
                    "description": "Session title, 2-5 words, English",
                },
                "summary": {
                    "type": "string",
                    "description": (
                        "1-2 sentences: problem, approach, key decisions. "
                        "Each call replaces previous, so include full arc."
                    ),
                },
                "tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Topic tags (e.g. ['architecture', 'refactoring'])",
                },
                "content": {
                    "type": "string",
                    "description": "Compact session content saved to a separate messages file for /load recovery",
                },
            },
            "required": ["session_id", "title", "summary"],
        },
    },
    {
        "name": "memory_remember",
        "description": (
            "Save a rule, preference, or fact to long-term memory as a .md file. "
            "Use explicit scope tags to route to the right directory."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "content": {
                    "type": "string",
                    "description": "The fact/rule text to save",
                },
                "tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": (
                        "Tags including a scope: 'universal', 'project/<name>', "
                        "or a language name. Plus topic tags like 'testing', 'git'."
                    ),
                },
                "language": {
                    "type": "string",
                    "description": (
                        "Target language for routing to languages/<lang>/. "
                        "Falls back to a language tag in 'tags' if omitted."
                    ),
                },
                "filename": {
                    "type": "string",
                    "description": "File stem (auto-derived from content if omitted)",
                },
            },
            "required": ["content", "tags"],
        },
    },
    {
        "name": "memory_search",
        "description": (
            "Search all memory files by tags, text, and dates. Covers facts, rules, "
            "and sessions — use tags to scope: 'universal', 'project/<name>', 'session', "
            "'rule', 'preference', etc. Results are sorted before slicing, so "
            "offset/since/until work correctly."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "All of these tags must be present",
                },
                "any_tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "At least one of these tags must be present",
                },
                "exclude_tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Skip files that have any of these tags",
                },
                "text": {
                    "type": "string",
                    "description": "Substring to search in file content (case-insensitive)",
                },
                "since": {
                    "type": "string",
                    "description": "ISO date (YYYY-MM-DD); skip files created before this date",
                },
                "until": {
                    "type": "string",
                    "description": "ISO date (YYYY-MM-DD); skip files created after this date",
                },
                "sort_by": {
                    "type": "string",
                    "enum": ["date", "modified"],
                    "description": (
                        "'date': front-matter creation date, newest first (default). "
                        "'modified': file mtime, most recently changed first."
                    ),
                },
                "limit": {
                    "type": "integer",
                    "description": "Max results to return (default: 20)",
                },
                "offset": {
                    "type": "integer",
                    "description": "Skip first N results for pagination (default: 0)",
                },
            },
        },
    },
    {
        "name": "memory_explore_tags",
        "description": (
            "Get an overview of all tags in the storage tree with file counts. "
            "Useful for orientation before searching."
        ),
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "memory_resolve_tags",
        "description": (
            "Normalize approximate tag names to existing tags via substring match. "
            "Use before memory_search to avoid misses from typos or short forms."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Approximate tag names to resolve",
                }
            },
            "required": ["tags"],
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


def _handle_tools_call(params: dict) -> dict:
    name = params.get("name")
    args: dict = params.get("arguments") or {}

    try:
        if name == "memory_session":
            path = storage.upsert_session(
                session_id=args["session_id"],
                project=args.get("project"),
                title=args["title"],
                summary=args["summary"],
                tags=args.get("tags") or [],
                content=args.get("content"),
            )
            return _text(f"Session saved → {path}")

        if name == "memory_remember":
            path = storage.remember(
                content_text=args["content"],
                tags=args.get("tags") or [],
                language=args.get("language"),
                filename=args.get("filename"),
            )
            return _text(f"Saved → {path}")

        if name == "memory_search":
            results = storage.search_facts(
                tags=args.get("tags"),
                any_tags=args.get("any_tags"),
                exclude_tags=args.get("exclude_tags"),
                text=args.get("text"),
                since=args.get("since"),
                until=args.get("until"),
                sort_by=args.get("sort_by", "date"),
                limit=args.get("limit", 20),
                offset=args.get("offset", 0),
            )
            return _text(json.dumps({"results": results}, indent=2, ensure_ascii=False))

        if name == "memory_explore_tags":
            return _text(
                json.dumps(storage.explore_tags(), indent=2, ensure_ascii=False)
            )

        if name == "memory_resolve_tags":
            resolved = storage.resolve_tags(args.get("tags") or [])
            return _text(
                json.dumps({"resolved": resolved}, ensure_ascii=False)
            )

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


_HANDLERS: dict[str, object] = {
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
