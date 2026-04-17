#!/usr/bin/env python3
# @ai-generated(solo)
"""Shared library package for ai-memory plugin.

All scripts (MCP server, hooks, skills) import storage and tags from here
instead of using sys.path hacks to reach mcp/.

Usage in any plugin script::

    import sys
    from pathlib import Path
    from lib import get_plugin_root

    sys.path.insert(0, str(get_plugin_root()))
    from lib import storage
    from lib.tags import parse_front_matter
"""

from pathlib import Path


def get_plugin_root() -> Path:
    """Return the absolute path to the plugin root directory (plugins/ai-memory/).

    Works regardless of the current working directory because it resolves
    relative to this file's location (__file__ is always the installed path).
    """
    return Path(__file__).resolve().parent.parent


def detect_agent(hook_data: dict) -> str:
    """Detect which AI agent is running from hook stdin payload.

    Detection order:
      1. Explicit ``agent`` field in payload (future-proof).
      2. ``transcript_path`` containing ``/.codex/`` → codex.
      3. ``model`` field matching known OpenAI model prefixes → codex.
      4. Default → claude.

    Args:
        hook_data: parsed JSON from hook stdin.

    Returns:
        Agent identifier string: ``"claude"`` or ``"codex"``.
    """
    # Explicit field — highest priority
    explicit = hook_data.get("agent")
    if explicit:
        return str(explicit).lower()

    # Transcript path heuristic
    tp = hook_data.get("transcript_path", "")
    if "/.codex/" in tp:
        return "codex"

    # Model heuristic — Codex uses OpenAI models (o3, o4-mini, gpt-*, etc.)
    model = hook_data.get("model", "")
    if model and any(model.startswith(p) for p in ("o1", "o3", "o4", "gpt-", "codex")):
        return "codex"

    return "claude"
