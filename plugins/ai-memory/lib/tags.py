#!/usr/bin/env python3
# @ai-generated(solo)
from __future__ import annotations
"""Tag parsing and derivation for ai-memory file-based storage.

Handles two sources of tags for each .md file:
  1. Path-derived: inferred from directory structure under AI_MEMORY_DIR
  2. Front-matter: explicit tags list in YAML front-matter

Public API:
  parse_front_matter(content: str) -> dict
  all_tags_for_file(path: Path, base_dir: Path, content: str) -> list[str]
  derive_tags_from_path(path: Path, base_dir: Path) -> list[str]
"""

import re
from pathlib import Path

# Matches YAML front-matter block: ---\n...\n---\n
_FRONT_MATTER_RE = re.compile(r"^---[ \t]*\n(.*?)\n---[ \t]*\n", re.DOTALL)


def parse_front_matter(content: str) -> dict:
    """Parse YAML front-matter from a markdown string.

    Handles only a flat subset of YAML: scalar values and inline lists.
    Nested structures and multi-line values are not supported.

    Args:
        content: full markdown file content

    Returns:
        Dict of key → raw string value. Empty dict if no front-matter.
    """
    m = _FRONT_MATTER_RE.match(content)
    if not m:
        return {}
    result = {}
    for line in m.group(1).splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if ":" in line:
            key, _, val = line.partition(":")
            result[key.strip()] = val.strip()
    return result


def parse_tags_field(val: str) -> list[str]:
    """Parse a YAML tags value into a list of tag strings.

    Accepts inline list syntax ('[tag1, tag2]') or bare single value ('tag1').

    Args:
        val: raw string value from front-matter tags key

    Returns:
        List of tag strings; empty list if val is blank.
    """
    val = val.strip()
    if not val:
        return []
    if val.startswith("[") and val.endswith("]"):
        items = val[1:-1].split(",")
        return [t.strip() for t in items if t.strip()]
    return [val]


# Directory → tag mapping rules, in priority order.
# Each entry is (required_parts_prefix, derived_tags_fn).
# derived_tags_fn receives the parts tuple starting after the top-level dir.
_PATH_RULES: list[tuple[str, object]] = []  # built below


def derive_tags_from_path(path: Path, base_dir: Path) -> list[str]:
    """Derive tags from the file's path relative to base_dir.

    Mapping:
      universal/...              → [universal]
      languages/<lang>/...       → [<lang>]
      projects/<name>/rules/...  → [project/<name>, rule]
      projects/<name>/sessions/  → [project/<name>, session]
      projects/<name>/...        → [project/<name>]
      sessions/...               → [session]

    Files directly in base_dir get no path-derived tags.

    Args:
        path: absolute path to the .md file
        base_dir: root of the ai-memory storage directory

    Returns:
        List of derived tags; empty list if path is not under base_dir
        or no rule matches.
    """
    try:
        rel = path.relative_to(base_dir)
    except ValueError:
        return []

    parts = rel.parts
    if len(parts) < 2:
        # File directly in base_dir — no structural tags
        return []

    top = parts[0]

    if top == "universal":
        # universal/rules/ → [universal, rule]; universal/ → [universal]
        if len(parts) >= 3 and parts[1] == "rules":
            return ["universal", "rule"]
        return ["universal"]

    if top == "languages" and len(parts) >= 3:
        # languages/<lang>/... → canonical tag is lang/<lang>
        return [f"lang/{parts[1]}"]

    if top == "projects" and len(parts) >= 3:
        project = parts[1]
        tags = [f"project/{project}"]
        if len(parts) >= 4:
            section = parts[2]
            if section == "rules":
                tags.append("rule")
            elif section == "sessions":
                tags.append("session")
        return tags

    if top == "sessions":
        return ["session"]

    return []


def all_tags_for_file(path: Path, base_dir: Path, content: str) -> list[str]:
    """Get the union of path-derived and front-matter tags for a file.

    Path-derived tags come first; front-matter tags are appended if not
    already present (deduplication preserves order).

    Args:
        path: absolute path to the .md file
        base_dir: root of the ai-memory storage directory
        content: file content (read by caller to avoid double-reads)

    Returns:
        Ordered, deduplicated list of all tags for this file.
    """
    fm = parse_front_matter(content)
    fm_tags = parse_tags_field(fm.get("tags", ""))
    path_tags = derive_tags_from_path(path, base_dir)

    seen: set[str] = set(path_tags)
    result: list[str] = list(path_tags)
    for t in fm_tags:
        if t not in seen:
            seen.add(t)
            result.append(t)
    return result
