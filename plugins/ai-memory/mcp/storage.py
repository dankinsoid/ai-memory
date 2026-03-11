#!/usr/bin/env python3
# @ai-generated(solo)
from __future__ import annotations
"""File-based storage operations for ai-memory.

All facts/rules are stored as individual .md files under AI_MEMORY_DIR
(default: ~/.claude/ai-memory/).  Sessions are stored as flat .md files
inside sessions/ directories, using full human-readable names:

  sessions/2026-03-11 Block 2 skills update.md          # summary (Obsidian-friendly)
  sessions/2026-03-11 Block 2 skills update messages.md  # compact content for /load

The summary file contains Obsidian wiki-links:
  messages: [[2026-03-11 Block 2 skills update messages]]
  continues: [[2026-03-10 Block 1 Python MCP server]]

No database or server required.

Public API:
  get_base_dir() -> Path
  search_facts(tags, any_tags, exclude_tags, text, since, until, sort_by, limit, offset) -> list[dict]
  search_sessions(project, text, since, until, sort_by, limit, offset) -> list[dict]
  upsert_session(session_id, project, title, summary, tags, content) -> str
  remember(content_text, tags, type_, filename) -> str
  explore_tags() -> dict
  resolve_tags(query_tags) -> list[str]
"""

import os
import re
from datetime import date
from pathlib import Path

from tags import (
    all_tags_for_file,
    derive_tags_from_path,
    parse_front_matter,
    parse_tags_field,
)


# ---------------------------------------------------------------------------
# Date helpers
# ---------------------------------------------------------------------------


def _parse_date(s: str) -> date | None:
    """Parse an ISO date string (YYYY-MM-DD) into a date object, or None."""
    try:
        return date.fromisoformat(s.strip())
    except (ValueError, AttributeError):
        return None


def _file_date(fm: dict) -> date | None:
    """Extract creation date from front-matter 'date:' field."""
    return _parse_date(fm.get("date", ""))


def _file_mtime(path: Path) -> float:
    """Return file modification time as a float (seconds since epoch)."""
    try:
        return path.stat().st_mtime
    except OSError:
        return 0.0


def get_base_dir() -> Path:
    """Return the ai-memory storage root, creating it if absent.

    Controlled by AI_MEMORY_DIR env var; defaults to ~/.claude/ai-memory/.
    """
    d = os.environ.get("AI_MEMORY_DIR", str(Path.home() / ".claude" / "ai-memory"))
    p = Path(d)
    p.mkdir(parents=True, exist_ok=True)
    return p


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _is_session_file(rel_parts: tuple[str, ...]) -> bool:
    """True if the relative path lives inside a sessions/ directory.

    Handles flat session files: sessions/foo.md → True
    Non-session: universal/some-rule.md → False
    """
    return "sessions" in rel_parts[:-1]


def _read_content(path: Path) -> str | None:
    """Read file text or return None on I/O error."""
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return None


def _safe_title(text: str) -> str:
    """Strip filesystem-invalid characters from a title string.

    Keeps spaces and most punctuation; removes chars that are invalid
    on macOS/Windows: / \\ : * ? \" < > |
    """
    return re.sub(r'[/\\:*?"<>|]', "", text).strip()


def _extract_summary_text(content: str) -> str | None:
    """Return the first non-empty line under the ## Summary heading."""
    in_summary = False
    for line in content.splitlines():
        if line.strip() == "## Summary":
            in_summary = True
            continue
        if in_summary:
            if line.startswith("##"):
                break
            stripped = line.strip()
            if stripped:
                return stripped
    return None


def _parse_wikilink(value: str) -> str | None:
    """Extract the page name from an Obsidian wiki-link [[name]], or None."""
    m = re.match(r"^\[\[(.+)\]\]$", value.strip())
    return m.group(1) if m else None


# ---------------------------------------------------------------------------
# Facts / rules search
# ---------------------------------------------------------------------------


def search_facts(
    tags: list[str] | None = None,
    any_tags: list[str] | None = None,
    exclude_tags: list[str] | None = None,
    text: str | None = None,
    since: str | None = None,
    until: str | None = None,
    sort_by: str = "date",
    limit: int = 20,
    offset: int = 0,
) -> list[dict]:
    """Search fact/rule files by tags, dates, and optional text.

    Scans all .md files under base_dir, excluding files in sessions/
    directories.  Results are sorted before slicing, so since/until/offset
    work correctly even when many files match.

    Args:
        tags: all of these tags must be present
        any_tags: at least one of these tags must be present
        exclude_tags: skip files that have any of these tags
        text: case-insensitive substring match against file content
        since: ISO date string (YYYY-MM-DD); skip files with date before this
        until: ISO date string; skip files with date after this
        sort_by: 'date' (front-matter date, newest first) or
                 'modified' (mtime, most recently changed first)
        limit: max results after sorting
        offset: skip first N results (for pagination)

    Returns:
        List of dicts with keys:
          path (str, relative to base_dir), tags (list[str]),
          type (str), date (str), content (str)
    """
    base = get_base_dir()
    since_d = _parse_date(since) if since else None
    until_d = _parse_date(until) if until else None
    text_lower = text.lower() if text else None

    candidates: list[dict] = []

    for md_file in base.rglob("*.md"):
        content = _read_content(md_file)
        if content is None:
            continue

        file_tags = all_tags_for_file(md_file, base, content)

        if tags and not all(t in file_tags for t in tags):
            continue
        if any_tags and not any(t in file_tags for t in any_tags):
            continue
        if exclude_tags and any(t in file_tags for t in exclude_tags):
            continue
        if text_lower and text_lower not in content.lower():
            continue

        fm = parse_front_matter(content)
        file_date = _file_date(fm)

        if since_d and (file_date is None or file_date < since_d):
            continue
        if until_d and (file_date is not None and file_date > until_d):
            continue

        candidates.append(
            {
                "path": str(md_file.relative_to(base)),
                "tags": file_tags,
                "type": fm.get("type", ""),
                "date": fm.get("date", ""),
                "content": content,
                # Internal sort key — stripped before returning
                "_mtime": _file_mtime(md_file),
                "_date": file_date,
            }
        )

    # Sort: newest first; mtime as tiebreaker for equal front-matter dates
    # date=None sorts last (treat as oldest)
    _epoch = date(1970, 1, 1)
    if sort_by == "modified":
        candidates.sort(key=lambda r: r["_mtime"], reverse=True)
    else:
        candidates.sort(
            key=lambda r: (r["_date"] or _epoch, r["_mtime"]),
            reverse=True,
        )

    # Strip internal keys before returning
    for r in candidates:
        del r["_mtime"]
        del r["_date"]

    return candidates[offset: offset + limit]


# ---------------------------------------------------------------------------
# Sessions search
# ---------------------------------------------------------------------------


def _is_messages_file(filename: str) -> bool:
    """True if this is a session messages file (ends with ' messages.md')."""
    return filename.endswith(" messages.md")


def _read_session_file(summary_path: Path, base: Path) -> dict | None:
    """Read a session summary .md file and return a session record, or None.

    The summary file must contain front-matter with at least an 'id' field.
    A paired messages file is expected at '<stem> messages.md'.

    Returns dict with keys: path, title, date, project, tags, id,
    continues, summary, messages_path, content (full summary.md text),
    _mtime (internal), _date (internal).
    """
    content = _read_content(summary_path)
    if content is None:
        return None

    fm = parse_front_matter(content)
    # Skip files without a session ID — they're likely not session summaries
    if not fm.get("id"):
        return None

    messages_stem = summary_path.stem + " messages"
    messages_path = summary_path.parent / f"{messages_stem}.md"

    return {
        "path": str(summary_path.relative_to(base)),
        "title": fm.get("title", summary_path.stem),
        "date": fm.get("date", ""),
        "project": fm.get("project", ""),
        "tags": parse_tags_field(fm.get("tags", "")),
        "id": fm.get("id", ""),
        "continues": fm.get("continues", ""),
        "summary": _extract_summary_text(content) or "",
        "messages_path": str(messages_path.relative_to(base)) if messages_path.exists() else None,
        "content": content,
        "_mtime": _file_mtime(summary_path),
        "_date": _file_date(fm),
    }


def search_sessions(
    project: str | None = None,
    text: str | None = None,
    since: str | None = None,
    until: str | None = None,
    sort_by: str = "date",
    limit: int = 5,
    offset: int = 0,
) -> list[dict]:
    """Return session summary records, sorted and filtered.

    Searches projects/<project>/sessions/ (if project given) or sessions/.
    Only reads summary .md files (skips ' messages.md' files).

    Args:
        project: restrict to this project's sessions dir
        text: case-insensitive substring match against summary.md content
        since: ISO date; skip sessions with date before this
        until: ISO date; skip sessions with date after this
        sort_by: 'date' (front-matter date, newest first) or
                 'modified' (mtime, newest first)
        limit: max sessions to return
        offset: skip first N results

    Returns:
        List of dicts with keys: path, title, date, project, tags,
        id, continues, summary, messages_path, content
    """
    base = get_base_dir()
    since_d = _parse_date(since) if since else None
    until_d = _parse_date(until) if until else None
    text_lower = text.lower() if text else None

    if project:
        # Strict filter: project= means only that project's sessions dir.
        search_dirs = [base / "projects" / project / "sessions"]
    else:
        search_dirs = [base / "sessions"]

    candidates: list[dict] = []
    for d in search_dirs:
        if not d.exists():
            continue
        for f in d.glob("*.md"):
            # Skip messages files — only read summary files
            if _is_messages_file(f.name):
                continue
            rec = _read_session_file(f, base)
            if rec is None:
                continue

            file_date = rec["_date"]
            if since_d and (file_date is None or file_date < since_d):
                continue
            if until_d and (file_date is not None and file_date > until_d):
                continue
            if text_lower and text_lower not in rec["content"].lower():
                continue

            candidates.append(rec)

    _epoch = date(1970, 1, 1)
    if sort_by == "modified":
        candidates.sort(key=lambda r: r["_mtime"], reverse=True)
    else:
        candidates.sort(
            key=lambda r: (r["_date"] or _epoch, r["_mtime"]),
            reverse=True,
        )

    for r in candidates:
        del r["_mtime"]
        del r["_date"]

    return candidates[offset: offset + limit]


# ---------------------------------------------------------------------------
# Upsert session
# ---------------------------------------------------------------------------


def _find_session_file(sessions_dir: Path, session_id: str) -> Path | None:
    """Locate an existing session summary file by id in front-matter."""
    if not sessions_dir.exists():
        return None
    for f in sessions_dir.glob("*.md"):
        if _is_messages_file(f.name):
            continue
        content = _read_content(f)
        if content and parse_front_matter(content).get("id") == session_id:
            return f
    return None


def upsert_session(
    session_id: str,
    project: str | None,
    title: str,
    summary: str,
    tags: list[str],
    content: str | None = None,
) -> str:
    """Create or update a session .md file pair.

    File naming:
      {date} {title}.md          — summary (front-matter + ## Summary)
      {date} {title} messages.md — compact content (written when content given)

    The summary file contains:
      messages: [[{date} {title} messages]]  (when content provided)
      continues: — left empty; caller or /save skill fills it if known

    Matches existing session by id field in summary front-matter; reuses
    the existing filename if found (preserves original date in name).

    Args:
        session_id: unique session identifier (UUID or similar)
        project: project name; selects projects/<project>/sessions/ dir
        title: short session title, 2-5 words
        summary: 1-2 sentence summary for quick reading
        tags: topic tags (path-derived tags are added automatically)
        content: optional compact content for the messages file

    Returns:
        Path to the session summary file, relative to base_dir.
    """
    base = get_base_dir()
    today = date.today().isoformat()

    if project:
        sessions_dir = base / "projects" / project / "sessions"
    else:
        sessions_dir = base / "sessions"
    sessions_dir.mkdir(parents=True, exist_ok=True)

    existing = _find_session_file(sessions_dir, session_id)
    if existing:
        # Reuse existing filename (keeps original creation date in name)
        summary_path = existing
        stem = existing.stem
    else:
        safe = _safe_title(title)
        stem = f"{today} {safe}"
        summary_path = sessions_dir / f"{stem}.md"

    tags_str = "[" + ", ".join(tags) + "]" if tags else "[]"

    messages_stem = f"{stem} messages"
    messages_link = f"[[{messages_stem}]]"

    fm_lines = ["---", f"id: {session_id}", f"date: {today}"]
    if project:
        fm_lines.append(f"project: {project}")
    fm_lines += [f"title: {title}", f"tags: {tags_str}"]
    if content:
        fm_lines.append(f"messages: {messages_link}")
    fm_lines += ["---", ""]

    summary_body = ["## Summary", "", summary, ""]

    summary_path.write_text("\n".join(fm_lines + summary_body), encoding="utf-8")

    if content:
        messages_path = sessions_dir / f"{messages_stem}.md"
        messages_path.write_text(content, encoding="utf-8")

    return str(summary_path.relative_to(base))


# ---------------------------------------------------------------------------
# Remember (save fact/rule)
# ---------------------------------------------------------------------------


_KNOWN_LANGS = {
    "clojure", "python", "javascript", "typescript", "go", "rust",
    "java", "kotlin", "swift", "ruby", "bash", "shell", "elixir",
    "haskell", "ocaml", "scala",
}


def _tags_to_dir(base: Path, tags: list[str], language: str | None = None) -> Path:
    """Choose storage directory based on explicit language or scope tags.

    Priority: project/<name> tag → explicit language param →
              language tag (fallback) → universal/
    """
    for t in tags:
        if t.startswith("project/"):
            project = t[len("project/"):]
            return base / "projects" / project / "rules"

    if language:
        return base / "languages" / language

    # Language from tags as fallback only
    for t in tags:
        if t in _KNOWN_LANGS:
            return base / "languages" / t

    return base / "universal"


def remember(
    content_text: str,
    tags: list[str],
    type_: str = "preference",
    filename: str | None = None,
    language: str | None = None,
) -> str:
    """Save a fact or rule as a .md file.

    Directory routing priority:
      1. project/<name> in tags → projects/<name>/rules/
      2. explicit language param → languages/<language>/
      3. language name in tags (fallback) → languages/<lang>/
      4. default → universal/

    Path-derived tags are excluded from front-matter to avoid duplication.

    Args:
        content_text: the fact/rule text to persist
        tags: tags including at least one scope tag
        type_: 'preference' | 'rule' | 'critical-rule'
        filename: file stem; auto-derived from content if omitted
        language: explicit target language (e.g. 'clojure', 'python')

    Returns:
        Path to the created/updated file, relative to base_dir.
    """
    base = get_base_dir()
    target_dir = _tags_to_dir(base, tags, language)
    target_dir.mkdir(parents=True, exist_ok=True)

    stem = filename or _safe_title(content_text)[:60] or "untitled"
    if not stem.endswith(".md"):
        stem += ".md"

    target = target_dir / stem

    # Exclude path-derived tags from front-matter to avoid duplication
    path_tags = set(derive_tags_from_path(target, base))
    fm_tags = [t for t in tags if t not in path_tags]
    tags_str = "[" + ", ".join(fm_tags) + "]" if fm_tags else "[]"

    today = date.today().isoformat()
    file_content = (
        f"---\ntags: {tags_str}\ntype: {type_}\ndate: {today}\n---\n\n{content_text}\n"
    )
    target.write_text(file_content, encoding="utf-8")
    return str(target.relative_to(base))


# ---------------------------------------------------------------------------
# Tag exploration
# ---------------------------------------------------------------------------


def explore_tags() -> dict:
    """Return a count of files per tag across the entire storage tree.

    Returns:
        Dict with key 'tags': list of {'name': str, 'count': int}
        sorted alphabetically by name.
    """
    base = get_base_dir()
    counts: dict[str, int] = {}

    for md_file in base.rglob("*.md"):
        content = _read_content(md_file)
        if content is None:
            continue
        for t in all_tags_for_file(md_file, base, content):
            counts[t] = counts.get(t, 0) + 1

    return {
        "tags": [{"name": k, "count": v} for k, v in sorted(counts.items())]
    }


# ---------------------------------------------------------------------------
# Tag resolution
# ---------------------------------------------------------------------------


def resolve_tags(query_tags: list[str]) -> list[str]:
    """Normalize approximate tag names to existing tags via substring match.

    For each query tag: exact match wins; otherwise all existing tags that
    contain the query as a substring (case-insensitive) are returned, up to 3.

    Args:
        query_tags: list of approximate or abbreviated tag names

    Returns:
        Ordered, deduplicated list of matched existing tags.
    """
    base = get_base_dir()
    all_tags: set[str] = set()

    for md_file in base.rglob("*.md"):
        content = _read_content(md_file)
        if content is None:
            continue
        all_tags.update(all_tags_for_file(md_file, base, content))

    resolved: list[str] = []
    seen: set[str] = set()

    for q in query_tags:
        if q in all_tags:
            if q not in seen:
                seen.add(q)
                resolved.append(q)
            continue
        q_lower = q.lower()
        matches = [
            t for t in sorted(all_tags)
            if q_lower in t.lower() or t.lower() in q_lower
        ]
        for m in matches[:3]:
            if m not in seen:
                seen.add(m)
                resolved.append(m)

    return resolved
