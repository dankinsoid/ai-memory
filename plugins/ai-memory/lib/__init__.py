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
