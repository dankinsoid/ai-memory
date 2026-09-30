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
import re
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
        last_compact: latest Claude Code compaction summary from the
            ``.compacts.md`` sidecar; when set, transcript_tail holds only
            the messages after that compaction
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
    facts: list[tuple[str, int]] | None = None  # (text, importance) pairs
    last_compact: str | None = None


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
    return _read_session_content(summary_path, session_id)


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
    return _read_session_content(found, parse_front_matter(content).get("id", ""))


def _read_session_content(summary_path: Path, session_id: str) -> SessionContent:
    """Build SessionContent from a session file and its compacts sidecar."""
    content = storage._read_content(summary_path) or ""
    fm = parse_front_matter(content)
    transcript = _extract_transcript_tail(content)
    last_compact = _read_last_compact(summary_path)
    if last_compact and transcript:
        transcript = _after_compact_marker(transcript, last_compact[0])
    return SessionContent(
        title=fm.get("title", summary_path.stem),
        compact=storage._extract_compact_text(content),
        summary=fm.get("summary"),
        session_id=session_id,
        transcript_tail=transcript,
        continues=fm.get("continues"),
        file_stem=summary_path.stem,
        branch=fm.get("branch"),
        commit_start=fm.get("commit_start"),
        commit_end=fm.get("commit_end"),
        facts=storage._extract_facts_text(content) or None,
        last_compact=last_compact[1] if last_compact else None,
    )


def format_for_load(
    sc: SessionContent, stem: str | None = None, budget: int | None = None,
) -> str:
    """Format SessionContent for /load skill — full recovery with smart truncation.

    Compact (or summary), last compaction, facts and transcript tail share
    one char budget, allocated in that order, with a share of the budget
    reserved for each later section.  The last compaction is shown right
    before the tail because the tail starts where that compaction ended.  Includes [[wikilink]]
    ref so the loaded session is trackable in the transcript via session-sync
    ref extraction.

    Appends a note indicating whether the content is the full transcript or a
    compact+tail subset, so the consuming agent knows whether more detail is
    available.

    Args:
        sc: loaded session content
        stem: file stem for [[wikilink]] ref (e.g. '2026-03-16 Title.abc12345')
        budget: total chars for compact + facts + tail (default LOAD_TOTAL_BUDGET)

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

    from lib.digest import (
        FACTS_LOAD_MAX, LOAD_TOTAL_BUDGET, LOAD_FACTS_SHARE, LOAD_TAIL_SHARE,
        LOAD_LAST_COMPACT_SHARE,
    )

    if budget is None:
        budget = LOAD_TOTAL_BUDGET
    last_compact_cap = int(budget * LOAD_LAST_COMPACT_SHARE)
    facts_min = int(budget * LOAD_FACTS_SHARE)
    tail_min  = int(budget * LOAD_TAIL_SHARE)
    has_facts = bool(sc.facts)
    has_tail  = bool(sc.transcript_tail)

    # Compact / summary — reserve minimums for subsequent parts before truncating
    if sc.compact:
        reserved     = (facts_min if has_facts else 0) + (tail_min if has_tail else 0)
        max_compact  = max(budget - reserved, 0)
        compact_text = sc.compact[:max_compact] if len(sc.compact) > max_compact else sc.compact
        parts.append(f"## Compact\n\n{compact_text}")
        budget -= len(compact_text)
    elif sc.summary:
        reserved     = (facts_min if has_facts else 0) + (tail_min if has_tail else 0)
        max_summary  = max(budget - reserved, 0)
        summary_text = sc.summary[:max_summary] if len(sc.summary) > max_summary else sc.summary
        parts.append(f"## Summary\n\n{summary_text}")
        budget -= len(summary_text)

    last_compact_part: str | None = None
    if sc.last_compact and budget > 0:
        reserved = (facts_min if has_facts else 0) + (tail_min if has_tail else 0)
        cap = min(last_compact_cap, max(budget - reserved, 0))
        text = sc.last_compact
        if len(text) > cap:
            text = text[:cap].rstrip() + "\n\n…(truncated)"
        if cap > 0:
            last_compact_part = f"## Last compaction\n\n{text}"
            budget -= len(text)

    # Facts — top by importance; reserve tail minimum before stopping
    if has_facts and budget > 0:
        selected = sc.facts
        if len(selected) > FACTS_LOAD_MAX:
            by_imp = sorted(
                enumerate(selected), key=lambda x: x[1][1], reverse=True,
            )[:FACTS_LOAD_MAX]
            by_imp.sort(key=lambda x: x[0])  # restore chronological order
            selected = [f for _, f in by_imp]
        facts_budget = max(budget - (tail_min if has_tail else 0), 0)
        fact_lines: list[str] = []
        for text, imp in selected:
            line = f"- [{imp}] {text}"
            if facts_budget - len(line) < 0 and fact_lines:
                break
            fact_lines.append(line)
            facts_budget -= len(line) + 1  # +1 for newline
            budget -= len(line) + 1
        if fact_lines:
            parts.append("## Facts\n\n" + "\n".join(fact_lines))

    if last_compact_part:
        parts.append(last_compact_part)

    # Transcript tail — gets whatever budget remains (at least tail_min was reserved)
    truncated = False
    if sc.transcript_tail and budget > 0:
        truncated = len(sc.transcript_tail) > budget
        tail = sc.transcript_tail[-budget:]
        # Avoid cutting mid-line
        newline = tail.find("\n")
        if newline != -1 and newline < len(tail) - 1:
            tail = tail[newline + 1:]
        if tail.strip():
            heading = "Recent messages"
            if sc.last_compact and not truncated:
                heading = "Messages since last compaction"
            parts.append(f"## {heading}\n\n{tail}")

    # Trailing note so the agent knows whether this is the full picture
    if not truncated and not sc.compact and not sc.last_compact:
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


_COMPACT_HEADING_RE = re.compile(r"^## Compact (\d+)[ \t]*$", re.MULTILINE)


def _read_last_compact(summary_path: Path) -> tuple[int, str] | None:
    """Return (index, text) of the last section in the session's compacts sidecar."""
    sidecar = summary_path.with_name(summary_path.stem + storage.COMPACTS_SUFFIX)
    text = storage._read_content(sidecar) if sidecar.exists() else None
    if not text:
        return None
    headings = list(_COMPACT_HEADING_RE.finditer(text))
    if not headings:
        return None
    last = headings[-1]
    body = text[last.end():].strip()
    return (int(last.group(1)), body) if body else None


def _after_compact_marker(transcript: str, index: int) -> str | None:
    """Slice the transcript after the marker of compact ``index``.

    A missing marker (sidecar and transcript out of sync) keeps the whole
    transcript: showing too much beats silently dropping messages.
    """
    marker = re.search(
        rf"\^compact-{index}[ \t]*$", transcript, re.MULTILINE,
    )
    if not marker:
        return transcript
    return transcript[marker.end():].strip() or None


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
