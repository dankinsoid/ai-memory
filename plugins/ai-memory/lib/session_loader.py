#!/usr/bin/env python3
# @ai-generated(guided)
"""Session content loader — shared by SessionStart hook and /load skill (via MCP).

Provides two main entry points:
  load_prev_session(project, current_session_id) — chaining: find previous session
      via prev-session cache, return its compact/summary content.
  load_session_by_id(session_id, project) — load session content by known ID.

Both return a SessionContent dataclass with title, compact, and summary fields.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from lib import storage
from lib.tags import parse_front_matter


@dataclass
class SessionContent:
    """Loaded session content for recovery/chaining.

    Attributes:
        title: session title from front-matter
        compact: compact notes (## Compact section), or None
        summary: session summary from front-matter, or None
        session_id: the loaded session's UUID
        transcript_tail: tail of ## Transcript section, or None
    """

    title: str
    compact: str | None
    summary: str | None
    session_id: str
    transcript_tail: str | None = None


def load_prev_session(project: str, current_session_id: str) -> SessionContent | None:
    """Find and load the previous session via prev-session cache.

    The cache is written by session-end hook on /clear, keyed by project name.
    Reads from SQLite state table first, falls back to legacy JSON file.

    Args:
        project: project name (used as cache key)
        current_session_id: current session UUID (to avoid self-match)

    Returns:
        SessionContent with previous session's data, or None if no chain found.
    """
    prev_id = _read_prev_session_id(project)
    if not prev_id or prev_id[:8] == current_session_id[:8]:
        return None
    return load_session_by_id(prev_id, project)


def load_session_by_id(session_id: str, project: str | None = None) -> SessionContent | None:
    """Load session content by session UUID.

    Searches project sessions dir first (if given), then generic sessions dir.

    Args:
        session_id: full session UUID
        project: optional project name for targeted search

    Returns:
        SessionContent, or None if session not found.
    """
    summary_path = _find_session(session_id, project)
    if not summary_path:
        return None

    content = storage._read_content(summary_path) or ""
    fm = parse_front_matter(content)

    return SessionContent(
        title=fm.get("title", summary_path.stem),
        compact=storage._extract_compact_text(content),
        summary=fm.get("summary"),
        session_id=session_id,
        transcript_tail=_extract_transcript_tail(content),
    )


def load_session_by_ref(ref: str) -> SessionContent | None:
    """Load session content by wikilink ref (file stem).

    Args:
        ref: wikilink stem, e.g. '2026-03-14 Some title.ca892878'

    Returns:
        SessionContent, or None if not found.
    """
    stem = ref.strip("[]")
    found = storage.find_file_by_stem(stem)
    if not found:
        return None

    content = storage._read_content(found) or ""
    fm = parse_front_matter(content)

    return SessionContent(
        title=fm.get("title", found.stem),
        compact=storage._extract_compact_text(content),
        summary=fm.get("summary"),
        session_id=fm.get("id", ""),
        transcript_tail=_extract_transcript_tail(content),
    )


def format_for_context(sc: SessionContent) -> str:
    """Format SessionContent for injection into agent context (SessionStart output).

    Prefers compact notes; falls back to summary.

    Args:
        sc: loaded session content

    Returns:
        Formatted markdown string.
    """
    body = sc.compact or sc.summary or ""
    return f"*{sc.title}*\n\n{body}" if body else f"*{sc.title}*"


def format_for_load(sc: SessionContent) -> str:
    """Format SessionContent for /load skill — full recovery with smart truncation.

    Shows compact + transcript tail (500 chars if compact exists, 2000 otherwise).
    Falls back to summary if no compact.

    Args:
        sc: loaded session content

    Returns:
        Formatted markdown string for deep recovery.
    """
    parts = [f"# Session Recovery\n\n*{sc.title}*"]

    if sc.compact:
        parts.append(f"## Compact\n\n{sc.compact}")
    elif sc.summary:
        parts.append(f"## Summary\n\n{sc.summary}")

    if sc.transcript_tail:
        # Shorter tail when compact exists (agent already has structured context)
        budget = 500 if sc.compact else 2000
        tail = sc.transcript_tail[-budget:]
        # Avoid cutting mid-line
        newline = tail.find("\n")
        if newline != -1 and newline < len(tail) - 1:
            tail = tail[newline + 1:]
        if tail.strip():
            parts.append(f"## Recent messages\n\n{tail}")

    return "\n\n".join(parts)


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _read_prev_session_id(project: str) -> str | None:
    """Read previous session ID from cache (SQLite → legacy JSON fallback).

    Args:
        project: project name for cache key

    Returns:
        Previous session UUID string, or None.
    """
    raw = None
    try:
        from lib.db import get_state
        raw = get_state(f"prev-session-{project}")
    except Exception:
        pass
    if not raw:
        cache_path = (
            Path.home() / ".claude" / "hooks" / "state" / f"prev-session-{project}.json"
        )
        try:
            raw = cache_path.read_text(encoding="utf-8")
        except OSError:
            return None
    try:
        return json.loads(raw).get("session_id")
    except (ValueError, AttributeError):
        return None


def _find_session(session_id: str, project: str | None) -> Path | None:
    """Locate session summary file by ID, searching project dir first.

    Args:
        session_id: full session UUID
        project: optional project name

    Returns:
        Path to session .md file, or None.
    """
    sessions_base = storage.get_sessions_base_dir()
    search_dirs: list[Path] = []
    if project:
        search_dirs.append(sessions_base / "projects" / project / "sessions")
    search_dirs.append(sessions_base / "sessions")

    for d in search_dirs:
        found = storage._find_session_file(d, session_id)
        if found:
            return found
    return None


def _extract_transcript_tail(content: str) -> str | None:
    """Extract ## Transcript section body from session content.

    The transcript is stored below a ``---`` divider in the session file.
    Returns the full transcript text (caller handles tail truncation).

    Args:
        content: full session file content

    Returns:
        Transcript text, or None if no transcript section.
    """
    lines = content.splitlines()
    start = None
    for i, line in enumerate(lines):
        if line.strip() == "## Transcript":
            start = i + 1
        elif start is not None and (line.startswith("## ") or line.strip() == "---"):
            return "\n".join(lines[start:i]).strip() or None
    if start is not None:
        return "\n".join(lines[start:]).strip() or None
    return None
