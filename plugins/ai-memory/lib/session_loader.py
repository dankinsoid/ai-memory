#!/usr/bin/env python3
# @ai-generated(guided)
"""Session content loader — shared by SessionStart hook and /load skill (via MCP).

Provides two main entry points:
  load_prev_session(project, current_session_id) — chaining: find previous session
      via prev-session cache, return its content.
  load_session_by_id(session_id, project) — load session content by known ID.

Both return a SessionContent dataclass. Use format_for_load() to render for display.
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
        continues: file stem of parent session, or None
        file_stem: file stem for [[wikilink]] refs, or None
        branch: git branch name, or None
        commit_start: short SHA at session start, or None
        commit_end: short SHA at session end, or None
    """

    title: str
    compact: str | None
    summary: str | None
    session_id: str
    transcript_tail: str | None = None
    continues: str | None = None
    file_stem: str | None = None
    branch: str | None = None
    commit_start: str | None = None
    commit_end: str | None = None


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
        continues=fm.get("continues"),
        file_stem=summary_path.stem,
        branch=fm.get("branch"),
        commit_start=fm.get("commit_start"),
        commit_end=fm.get("commit_end"),
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
        continues=fm.get("continues"),
        file_stem=found.stem,
        branch=fm.get("branch"),
        commit_start=fm.get("commit_start"),
        commit_end=fm.get("commit_end"),
    )


def format_for_load(sc: SessionContent, stem: str | None = None) -> str:
    """Format SessionContent for /load skill — full recovery with smart truncation.

    Shows compact + transcript tail (1000 chars if compact exists, 4000 otherwise).
    Falls back to summary if no compact.  Includes [[wikilink]] ref so the loaded
    session is trackable in the transcript via session-sync ref extraction.

    Appends a note indicating whether the content is the full transcript or a
    compact+tail subset, so the consuming agent knows whether more detail is
    available.

    Args:
        sc: loaded session content
        stem: file stem for [[wikilink]] ref (e.g. '2026-03-16 Title.abc12345')

    Returns:
        Formatted markdown string for deep recovery.
    """
    stem = stem or sc.file_stem
    header = f"# Session Recovery\n\n*{sc.title}*"
    if stem:
        header += f"  [[{stem}]]"
    if sc.continues:
        header += f"\n\nContinues: [[{sc.continues}]]"
    # Git context — branch and commit range from the session
    git_parts: list[str] = []
    if sc.branch:
        git_parts.append(f"branch: `{sc.branch}`")
    if sc.commit_start and sc.commit_end and sc.commit_start != sc.commit_end:
        git_parts.append(f"commits: `{sc.commit_start}..{sc.commit_end}`")
    elif sc.commit_start:
        git_parts.append(f"commit: `{sc.commit_start}`")
    elif sc.commit_end:
        git_parts.append(f"commit: `{sc.commit_end}`")
    if git_parts:
        header += "\n\n" + " | ".join(git_parts)
    parts = [header]

    if sc.compact:
        parts.append(f"## Compact\n\n{sc.compact}")
    elif sc.summary:
        parts.append(f"## Summary\n\n{sc.summary}")

    truncated = False
    if sc.transcript_tail:
        # Shorter tail when compact exists (agent already has structured context)
        budget = 1000 if sc.compact else 4000
        truncated = len(sc.transcript_tail) > budget
        tail = sc.transcript_tail[-budget:]
        # Avoid cutting mid-line
        newline = tail.find("\n")
        if newline != -1 and newline < len(tail) - 1:
            tail = tail[newline + 1:]
        if tail.strip():
            parts.append(f"## Recent messages\n\n{tail}")

    # Trailing note so the agent knows whether this is the full picture
    if not truncated and not sc.compact:
        parts.append("_This is the full session transcript._")
    else:
        parts.append(
            "_Compact notes + recent messages shown — this should be sufficient for recovery. "
            "Only call `memory_load_session` if specific detail is missing._"
        )

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
    base = storage.get_base_dir()
    search_dirs: list[Path] = []
    if project:
        search_dirs.append(base / "projects" / project / "sessions")
    search_dirs.append(base / "sessions")

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
