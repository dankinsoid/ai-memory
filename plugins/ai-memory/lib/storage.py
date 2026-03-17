#!/usr/bin/env python3
# @ai-generated(solo)
from __future__ import annotations
"""File-based storage operations for ai-memory.

All facts/rules are stored as individual .md files under AI_MEMORY_DIR
(default: ~/.claude/ai-memory/).  Sessions are stored as flat .md files
inside sessions/ directories, using full human-readable names:

  sessions/2026-03-11 Block 2 skills update.md          # summary + compact (Obsidian-friendly)
  sessions/2026-03-11 Block 2 skills update messages.md  # full conversation transcript

The summary file contains:
  ## Summary — 1-2 sentence arc
  ## Compact — detailed /save notes (if agent ran /save)

The summary file may contain an Obsidian wiki-link to its messages file
in the body (not front-matter):
  [[2026-03-11 Block 2 skills update messages]]  (added by Stop hook)

No database or server required.

Public API:
  get_base_dir() -> Path
  search_facts(tags, any_tags, exclude_tags, query, since, until, sort_by, limit, offset) -> list[dict]
  search_sessions(project, text, since, until, sort_by, limit, offset) -> list[dict]
  upsert_session(session_id, project, title, summary, tags, compact) -> str
  remember(content_text, tags, title, language) -> str
  reindex() -> dict
  find_file_by_stem(stem) -> Path | None
  explore_tags() -> dict
  get_stats() -> dict
  resolve_tags(query_tags) -> list[str]
"""

import json
import os
import re
import uuid
from datetime import date
from pathlib import Path

from .tags import (
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
    p = Path(d).expanduser()
    p.mkdir(parents=True, exist_ok=True)
    return p


def get_sessions_base_dir() -> Path:
    """Return the root under which sessions/ and projects/*/sessions/ live.

    When AI_MEMORY_SESSIONS_DIR is set (e.g. an Obsidian vault), sessions are
    stored there instead of inside AI_MEMORY_DIR.  Facts and rules always stay
    in AI_MEMORY_DIR regardless.
    """
    override = os.environ.get("AI_MEMORY_SESSIONS_DIR")
    if override:
        p = Path(override).expanduser()
        p.mkdir(parents=True, exist_ok=True)
        return p
    return get_base_dir()


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
    """Normalize a title string for use in a filename.

    Removes chars invalid on macOS/Windows (/ \\ : * ? " < > |),
    removes dots (used as separator in .{session_id}.md naming),
    collapses runs of whitespace to a single space, and strips ends.
    """
    cleaned = re.sub(r'[/\\:*?"<>|.]', "", text)
    return re.sub(r'\s+', " ", cleaned).strip()


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


# ---------------------------------------------------------------------------
# Facts / rules search
# ---------------------------------------------------------------------------


def _matches_filters(
    file_tags: list[str],
    file_date: date | None,
    tags: list[str] | None,
    any_tags: list[str] | None,
    exclude_tags: list[str] | None,
    since_d: date | None,
    until_d: date | None,
) -> bool:
    """Check whether a fact/session record passes tag and date filters.

    Args:
        file_tags:    tags derived from the file (path + front-matter)
        file_date:    front-matter creation date (or None)
        tags:         all must be present (AND)
        any_tags:     at least one must be present (OR)
        exclude_tags: none may be present
        since_d:      skip if file_date < since_d
        until_d:      skip if file_date > until_d

    Returns:
        True if the record passes all filters, False otherwise.
    """
    if tags and not all(t in file_tags for t in tags):
        return False
    if any_tags and not any(t in file_tags for t in any_tags):
        return False
    if exclude_tags and any(t in file_tags for t in exclude_tags):
        return False
    if since_d and (file_date is None or file_date < since_d):
        return False
    if until_d and (file_date is not None and file_date > until_d):
        return False
    return True


def search_facts(
    tags: list[str] | None = None,
    any_tags: list[str] | None = None,
    exclude_tags: list[str] | None = None,
    query: str | None = None,
    since: str | None = None,
    until: str | None = None,
    sort_by: str = "date",
    limit: int = 20,
    offset: int = 0,
) -> list[dict]:
    """Search facts, rules, and sessions by tags, dates, or semantic query.

    Two modes:
      - **query** (semantic): vector cosine search across the "content"
        collection, which holds both facts and sessions.  Results are
        scored; tag/date filters are applied as post-filters.  Requires
        OPENAI_API_KEY; returns empty list when vectors are disabled.
      - **no query** (structured): full file-scan under base_dir with
        tag intersection/union, date range, and sort.

    Sessions live in the same vector collection as facts, distinguished
    by a "session" tag.  Use ``tags=["session"]`` or
    ``exclude_tags=["session"]`` to scope.

    Args:
        tags: all of these tags must be present
        any_tags: at least one of these tags must be present
        exclude_tags: skip files that have any of these tags
        query: semantic search string (natural language); when provided
               results are ranked by cosine similarity
        since: ISO date string (YYYY-MM-DD); skip files with date before this
        until: ISO date string; skip files with date after this
        sort_by: 'date' (front-matter date, newest first) or
                 'modified' (mtime, most recently changed first);
                 ignored for semantic search (always sorted by score)
        limit: max results after sorting
        offset: skip first N results (for pagination)

    Returns:
        List of dicts with keys:
          path (str), tags (list[str]), date (str), content (str).
          Semantic results additionally include score (float).
    """
    since_d = _parse_date(since) if since else None
    until_d = _parse_date(until) if until else None

    if query:
        from .vector_store import content_store
        if not content_store.enabled:
            return []
        return _query_search(
            query, content_store,
            tags, any_tags, exclude_tags, since_d, until_d,
            limit, offset,
        )

    return _filescan_search(
        tags, any_tags, exclude_tags, since_d, until_d,
        sort_by, limit, offset,
    )


def _query_search(
    query: str,
    store: object,
    tags: list[str] | None,
    any_tags: list[str] | None,
    exclude_tags: list[str] | None,
    since_d: date | None,
    until_d: date | None,
    limit: int,
    offset: int,
) -> list[dict]:
    """Semantic vector search with tag/date post-filtering.

    Overfetches from the vector store to account for post-filter losses,
    then enriches each hit by reading the source file.
    Sorted by cosine score descending.
    """
    from .vector_store import ContentVectorStore
    assert isinstance(store, ContentVectorStore)

    base = get_base_dir()
    sessions_base = get_sessions_base_dir()

    hits = store.search(query, top_k=max(limit + offset, 20) * 3, threshold=0.25)

    candidates: list[dict] = []
    for h in hits:
        file_path = base / h.id
        if not file_path.exists():
            file_path = sessions_base / h.id
        content = _read_content(file_path)
        if content is None:
            continue

        try:
            file_tags = all_tags_for_file(file_path, base, content)
        except ValueError:
            file_tags = h.payload.get("tags", [])

        fm = parse_front_matter(content)
        file_date = _file_date(fm)

        if not _matches_filters(file_tags, file_date, tags, any_tags, exclude_tags, since_d, until_d):
            continue

        candidates.append({
            "ref": f"[[{Path(h.id).stem}]]",
            "path": h.id,
            "tags": file_tags,
            "date": fm.get("date", ""),
            "content": content,
            "score": round(h.score, 4),
        })

    return candidates[offset: offset + limit]


def _filescan_search(
    tags: list[str] | None,
    any_tags: list[str] | None,
    exclude_tags: list[str] | None,
    since_d: date | None,
    until_d: date | None,
    sort_by: str,
    limit: int,
    offset: int,
) -> list[dict]:
    """Structured search — SQL index when available, filesystem fallback."""
    from .db import is_populated

    if is_populated():
        return _sql_search(tags, any_tags, exclude_tags, since_d, until_d,
                           sort_by, limit, offset)

    return _raw_filescan_search(tags, any_tags, exclude_tags, since_d, until_d,
                                sort_by, limit, offset)


def _sql_search(
    tags: list[str] | None,
    any_tags: list[str] | None,
    exclude_tags: list[str] | None,
    since_d: date | None,
    until_d: date | None,
    sort_by: str,
    limit: int,
    offset: int,
) -> list[dict]:
    """Search using SQLite file index — filters in SQL, content from files."""
    from .db import get_connection

    conn = get_connection()
    conditions: list[str] = []
    params: list = []

    # AND tags: file must have ALL specified tags
    if tags:
        for t in tags:
            conditions.append(
                "rel_path IN (SELECT rel_path FROM file_tags WHERE tag = ?)"
            )
            params.append(t)

    # OR tags: file must have AT LEAST ONE
    if any_tags:
        placeholders = ",".join("?" * len(any_tags))
        conditions.append(
            f"rel_path IN (SELECT rel_path FROM file_tags WHERE tag IN ({placeholders}))"
        )
        params.extend(any_tags)

    # Exclude tags: file must have NONE
    if exclude_tags:
        placeholders = ",".join("?" * len(exclude_tags))
        conditions.append(
            f"rel_path NOT IN (SELECT rel_path FROM file_tags WHERE tag IN ({placeholders}))"
        )
        params.extend(exclude_tags)

    if since_d:
        conditions.append("date >= ?")
        params.append(since_d.isoformat())
    if until_d:
        conditions.append("date <= ?")
        params.append(until_d.isoformat())

    where = " AND ".join(conditions) if conditions else "1=1"
    order = "mtime DESC" if sort_by == "modified" else "date DESC, mtime DESC"

    sql = (
        f"SELECT rel_path, tags_json, date FROM files "
        f"WHERE {where} ORDER BY {order} LIMIT ? OFFSET ?"
    )
    params.extend([limit, offset])

    rows = conn.execute(sql, params).fetchall()

    base = get_base_dir()
    results: list[dict] = []
    for rel_path, tags_json, date_str in rows:
        content = _read_content(base / rel_path)
        if content is None:
            continue
        results.append({
            "ref": f"[[{Path(rel_path).stem}]]",
            "path": rel_path,
            "tags": json.loads(tags_json) if tags_json else [],
            "date": date_str or "",
            "content": content,
        })
    return results


def _raw_filescan_search(
    tags: list[str] | None,
    any_tags: list[str] | None,
    exclude_tags: list[str] | None,
    since_d: date | None,
    until_d: date | None,
    sort_by: str,
    limit: int,
    offset: int,
) -> list[dict]:
    """Filesystem fallback — rglob + tag/date filtering, no index."""
    base = get_base_dir()
    candidates: list[dict] = []

    for md_file in base.rglob("*.md"):
        content = _read_content(md_file)
        if content is None:
            continue

        file_tags = all_tags_for_file(md_file, base, content)
        fm = parse_front_matter(content)
        file_date = _file_date(fm)

        if not _matches_filters(file_tags, file_date, tags, any_tags, exclude_tags, since_d, until_d):
            continue

        rel = str(md_file.relative_to(base))
        candidates.append({
            "ref": f"[[{Path(rel).stem}]]",
            "path": rel,
            "tags": file_tags,
            "date": fm.get("date", ""),
            "content": content,
            "_mtime": _file_mtime(md_file),
            "_date": file_date,
        })

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
# Sessions search
# ---------------------------------------------------------------------------


def _is_messages_file(filename: str) -> bool:
    """True if this is a session messages file (ends with '.messages.md')."""
    return filename.endswith(".messages.md")


def find_file_by_stem(stem: str) -> Path | None:
    """Resolve a wikilink stem to an absolute file path.

    Searches both base_dir (facts/rules) and sessions_base (sessions).
    Skips .messages.md files. Returns None if no match found.

    Args:
        stem: filename without extension, e.g. 'always-run-regression-test'
    """
    for root in (get_base_dir(), get_sessions_base_dir()):
        for md_file in root.rglob("*.md"):
            if md_file.stem == stem and not _is_messages_file(md_file.name):
                return md_file
    return None


def _read_session_file(summary_path: Path, base: Path) -> dict | None:
    """Read a session summary .md file and return a session record, or None.

    The summary file must contain front-matter with at least an 'id' field.
    A paired messages file is expected at '<stem> messages.md'.

    Returns dict with keys: path, title, date, project, tags, id,
    summary, messages_path, content (full summary.md text),
    _mtime (internal), _date (internal).
    """
    content = _read_content(summary_path)
    if content is None:
        return None

    fm = parse_front_matter(content)
    # Skip files without a session ID — they're likely not session summaries
    if not fm.get("id"):
        return None

    messages_stem = summary_path.stem + ".messages"
    messages_path = summary_path.parent / f"{messages_stem}.md"

    return {
        "path": str(summary_path.relative_to(base)),
        "title": fm.get("title", summary_path.stem),
        "date": fm.get("date", ""),
        "project": fm.get("project", ""),
        "tags": parse_tags_field(fm.get("tags", "")),
        "id": fm.get("id", ""),
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

    Searches projects/<project>/sessions/ (if project given) or sessions/,
    including date subdirectories (YYYY-MM-DD/) and flat layout for backward
    compatibility. Only reads summary .md files (skips .messages.md files).

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
        id, summary, messages_path, content
    """
    base = get_base_dir()
    sessions_base = get_sessions_base_dir()
    since_d = _parse_date(since) if since else None
    until_d = _parse_date(until) if until else None
    text_lower = text.lower() if text else None

    if project:
        # Strict filter: project= means only that project's sessions dir.
        search_dirs = [sessions_base / "projects" / project / "sessions"]
    else:
        search_dirs = [sessions_base / "sessions"]

    # _read_session_file uses base for relative path — use sessions_base when overridden
    path_root = sessions_base

    candidates: list[dict] = []
    for d in search_dirs:
        if not d.exists():
            continue
        for f in d.rglob("*.md"):
            # Skip messages files — only read summary files
            if _is_messages_file(f.name):
                continue
            rec = _read_session_file(f, path_root)
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


def _find_session_file(sessions_parent: Path, session_id: str) -> Path | None:
    """Locate an existing session summary file by session_id suffix in filename.

    Searches date subdirs (sessions_parent/YYYY-MM-DD/) and the flat
    sessions_parent/ directory for backward compatibility with pre-date-subdir
    layout.

    New format: {date} {title}.{session_id[:8]}.md — O(1) lookup via glob suffix.
    Falls back to front-matter scan for legacy files without the id suffix.

    Args:
        sessions_parent: parent sessions directory (e.g. projects/<name>/sessions/)
        session_id: full session UUID

    Returns:
        Path to the session summary .md file, or None if not found.
    """
    if not sessions_parent.exists():
        return None
    sid8 = session_id[:8]
    # Fast path: search recursively for files with the id embedded in the filename
    for f in sessions_parent.rglob(f"*.{sid8}.md"):
        if not _is_messages_file(f.name):
            return f
    # Slow path: legacy files without id suffix — scan front-matter
    for f in sessions_parent.rglob("*.md"):
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
    compact: str | None = None,
    branch: str | None = None,
    commit_start: str | None = None,
    commit_end: str | None = None,
) -> str:
    """Create or update a session summary .md file.

    File naming:
      {date} {title}.md — summary file: front-matter + ## Summary [+ ## Compact]

    Matches existing session by id field in summary front-matter; reuses
    the existing filename if found (preserves original date in name).

    Sessions are written under AI_MEMORY_SESSIONS_DIR (or AI_MEMORY_DIR as
    fallback) so users can point them at an Obsidian vault independently of
    where facts/rules live.

    Args:
        session_id: unique session identifier (UUID or similar)
        project: project name; selects projects/<project>/sessions/ dir
        title: short session title, 2-5 words
        summary: 1-2 sentence summary for quick reading
        tags: topic tags (path-derived tags are added automatically)
        compact: optional detailed notes from /save (written as ## Compact section)
        branch: git branch name at session start
        commit_start: short SHA of HEAD at session start
        commit_end: short SHA of HEAD at save time

    Returns:
        Path to the session summary file, relative to AI_MEMORY_DIR base.
    """
    base = get_base_dir()
    sessions_base = get_sessions_base_dir()
    today = date.today().isoformat()

    if project:
        sessions_parent = sessions_base / "projects" / project / "sessions"
    else:
        sessions_parent = sessions_base / "sessions"

    existing = _find_session_file(sessions_parent, session_id)
    is_new = existing is None

    if existing:
        # Reuse existing filename (keeps original creation date in name)
        summary_path = existing
        stem = existing.stem
        # Preserve git context from previous writes if not overridden
        prev_fm = parse_front_matter(existing.read_text(encoding="utf-8"))
        if not branch:
            branch = prev_fm.get("branch")
        if not commit_start:
            commit_start = prev_fm.get("commit_start")
    else:
        safe = _safe_title(title)
        stem = f"{today} {safe}.{session_id[:8]}"
        # New sessions go into a date subdirectory
        sessions_dir = sessions_parent / today
        sessions_dir.mkdir(parents=True, exist_ok=True)
        summary_path = sessions_dir / f"{stem}.md"

    tags_str = "[" + ", ".join(tags) + "]" if tags else "[]"

    fm_lines = ["---", f"id: {session_id}", f"date: {today}"]
    if project:
        fm_lines.append(f"project: {project}")
    fm_lines += [f"title: {title}", f"tags: {tags_str}"]
    if branch:
        fm_lines.append(f"branch: {branch}")
    if commit_start:
        fm_lines.append(f"commit_start: {commit_start}")
    if commit_end:
        fm_lines.append(f"commit_end: {commit_end}")
    fm_lines += ["---", ""]

    summary_body = ["## Summary", "", summary, ""]
    if compact:
        summary_body += ["## Compact", "", compact, ""]

    # Preserve existing transcript section if present (appended by session-sync hook).
    # The transcript block starts with "---\n\n## Transcript" divider.
    if summary_path.exists():
        existing_content = summary_path.read_text(encoding="utf-8")
        transcript_marker = "\n## Transcript\n"
        idx = existing_content.find(transcript_marker)
        if idx != -1:
            # Find the --- divider preceding the transcript
            divider_idx = existing_content.rfind("\n---\n", 0, idx)
            start = divider_idx + 1 if divider_idx != -1 else idx + 1
            summary_body.append(existing_content[start:].rstrip())
            summary_body.append("")

    summary_path.write_text("\n".join(fm_lines + summary_body), encoding="utf-8")

    # Update SQLite file index synchronously.
    # Only index when session file lives under base_dir (not a separate sessions_base).
    from .db import index_file as _index_file
    try:
        _rel = str(summary_path.relative_to(base))
        _index_file(rel_path=_rel, abs_path=summary_path, base_dir=base)
    except (ValueError, Exception):
        # ValueError: sessions_base differs from base — file not under base_dir
        # Other exceptions: next reindex will catch it
        pass

    # Embed session content for semantic search.
    # Combine title + summary + compact for maximum semantic coverage.
    # Sessions live in the same "content" collection as facts, distinguished
    # by a "session" tag in the payload tags list.
    from .vector_store import content_store
    embed_text = f"{title}. {summary}"
    if compact:
        embed_text += f"\n{compact}"
    try:
        rel = str(summary_path.relative_to(base))
    except ValueError:
        rel = str(summary_path.relative_to(sessions_base))
    session_tags = list(tags) if tags else []
    if "session" not in session_tags:
        session_tags.append("session")
    content_store.upsert(
        id=rel,
        text=embed_text,
        payload={
            "path": rel,
            "tags": session_tags,
            "title": title,
            "project": project or "",
            "session_id": session_id,
        },
    )

    # Return path relative to base_dir (facts root) when sessions_base == base,
    # or relative to sessions_base when AI_MEMORY_SESSIONS_DIR is overridden so
    # callers can still locate the file.
    try:
        return str(summary_path.relative_to(base))
    except ValueError:
        # sessions_base is a different root — return relative to sessions_base
        return str(summary_path.relative_to(sessions_base))


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
              lang/<name> tag → bare language name tag (fallback) →
              universal/rules/ (if rule tag) → universal/
    """
    for t in tags:
        if t.startswith("project/"):
            project = t[len("project/"):]
            if "rule" in tags:
                return base / "projects" / project / "rules"
            return base / "projects" / project / "facts"

    if language:
        return base / "languages" / language

    # Explicit lang/<name> tag — canonical format
    for t in tags:
        if t.startswith("lang/"):
            return base / "languages" / t[len("lang/"):]

    # Bare language name as fallback (e.g. "clojure", "python")
    for t in tags:
        if t in _KNOWN_LANGS:
            return base / "languages" / t

    # Universal rules go to universal/rules/ so the 'rule' tag is path-derived
    if "rule" in tags:
        return base / "universal" / "rules"

    return base / "universal"


def remember(
    content_text: str,
    tags: list[str],
    title: str | None = None,
    language: str | None = None,
) -> str:
    """Save a fact or rule as a .md file.

    Directory routing priority:
      1. project/<name> in tags → projects/<name>/rules/
      2. explicit language param → languages/<language>/
      3. language name in tags (fallback) → languages/<lang>/
      4. rule tag → universal/rules/
      5. default → universal/

    Path-derived tags are excluded from front-matter to avoid duplication.
    Fact type (rule vs preference) is encoded via directory: files in rules/ get
    the 'rule' tag automatically from their path.

    Args:
        content_text: the fact/rule text to persist
        tags: tags including at least one scope tag
        title: descriptive filename stem; auto-derived from content if omitted
        language: explicit target language (e.g. 'clojure', 'python')

    Returns:
        Path to the created/updated file, relative to base_dir.
    """
    base = get_base_dir()
    target_dir = _tags_to_dir(base, tags, language)
    target_dir.mkdir(parents=True, exist_ok=True)

    name = title or _safe_title(content_text)[:60] or "untitled"
    # Short random suffix for uniqueness (same scheme as sessions use sid8)
    uid4 = uuid.uuid4().hex[:4]
    stem = f"{name}.{uid4}"
    filename = f"{stem}.md"

    target = target_dir / filename

    # Write all tags to front-matter (including path-derived ones)
    tags_str = "[" + ", ".join(tags) + "]" if tags else "[]"

    today = date.today().isoformat()
    file_content = (
        f"---\ntags: {tags_str}\ndate: {today}\n---\n\n{content_text}\n"
    )
    target.write_text(file_content, encoding="utf-8")

    # Update SQLite file index synchronously so subsequent reads see this file.
    from .db import index_file as _index_file
    try:
        _index_file(rel_path=str(target.relative_to(base)), abs_path=target, base_dir=base)
    except Exception:
        pass  # next reindex will catch it

    # Embed any new tags so future resolve_tags calls can match against them.
    # Called after write so a file error doesn't block vector storage.
    from .vector_store import tag_store, content_store
    tag_store.upsert(tags)

    # Embed fact content for semantic search (first paragraph only —
    # shorter text matches query length better and concentrates the vector).
    rel_path = str(target.relative_to(base))
    content_store.upsert(
        id=rel_path,
        text=first_paragraph(content_text),
        payload={"path": rel_path, "tags": tags},
    )

    return rel_path


# ---------------------------------------------------------------------------
# Reindex — backfill vector embeddings for existing files
# ---------------------------------------------------------------------------


def reindex() -> dict:
    """Embed all fact and session files that are missing from the vector store.

    Scans all .md files under base_dir (facts/rules) and sessions_base
    (session summaries).  For each file, computes the text that would be
    embedded and checks its MD5 against the stored payload.  Files with
    matching MD5 are skipped; new or changed files are embedded.

    Uses ``upsert_batch`` internally to minimise embedding API calls.

    Returns:
        Dict with keys: total (int), embedded (int), skipped (int).
        ``total`` = files scanned, ``embedded`` = newly embedded,
        ``skipped`` = already up-to-date.
    """
    from .vector_store import content_store

    if not content_store.enabled:
        return {"total": 0, "embedded": 0, "skipped": 0, "error": "vectorization disabled"}

    base = get_base_dir()
    sessions_base = get_sessions_base_dir()

    items: list[tuple[str, str, dict]] = []

    # --- Facts / rules ---
    for md_file in base.rglob("*.md"):
        rel_parts = md_file.relative_to(base).parts
        if _is_session_file(rel_parts):
            continue
        content = _read_content(md_file)
        if content is None:
            continue
        file_tags = all_tags_for_file(md_file, base, content)
        # Strip front-matter, embed only body text
        fm = parse_front_matter(content)
        body = _body_text(content)
        if not body.strip():
            continue
        rel_path = str(md_file.relative_to(base))
        items.append((rel_path, first_paragraph(body), {"path": rel_path, "tags": file_tags}))

    # --- Sessions ---
    for sessions_dir in _all_session_dirs(sessions_base):
        for f in sessions_dir.rglob("*.md"):
            if _is_messages_file(f.name):
                continue
            rec = _read_session_file(f, sessions_base)
            if rec is None:
                continue
            title = rec.get("title", "")
            summary = rec.get("summary", "")
            # Read compact section if present
            compact = _extract_compact_text(rec.get("content", ""))
            embed_text = f"{title}. {summary}"
            if compact:
                embed_text += f"\n{compact}"
            if not embed_text.strip():
                continue
            try:
                rel = str(f.relative_to(base))
            except ValueError:
                rel = str(f.relative_to(sessions_base))
            session_tags = rec.get("tags", [])
            if "session" not in session_tags:
                session_tags = list(session_tags) + ["session"]
            items.append((rel, embed_text, {
                "path": rel,
                "tags": session_tags,
                "title": title,
                "project": rec.get("project", ""),
                "session_id": rec.get("id", ""),
            }))

    total = len(items)
    embedded = content_store.upsert_batch(items)

    return {"total": total, "embedded": embedded, "skipped": total - embedded}


def _body_text(content: str) -> str:
    """Return file content with front-matter stripped.

    Removes the leading ``---`` ... ``---`` block if present.
    """
    lines = content.splitlines()
    if not lines or lines[0].strip() != "---":
        return content
    for i, line in enumerate(lines[1:], 1):
        if line.strip() == "---":
            return "\n".join(lines[i + 1:])
    return content


def first_paragraph(body: str) -> str:
    """Return the first meaningful paragraph of *body* text.

    *body* must not contain front-matter (strip it before calling).

    Scans paragraphs (blocks separated by blank lines) and returns the
    first one that looks like human-readable text.  If a ``## `` heading
    immediately precedes it, the heading is included for context.

    Non-text noise (rules, separators, metadata leftovers) is skipped.
    """
    text = body.strip()
    if not text:
        return text
    paragraphs = re.split(r"\n\n+", text)

    # Walk paragraphs looking for the first meaningful one.
    pending_heading: str | None = None
    for p in paragraphs:
        stripped = p.strip()
        if not stripped:
            continue
        if stripped.startswith("#"):
            # Remember heading — include it if the next paragraph is meaningful.
            pending_heading = stripped
            continue
        if _is_meaningful(p):
            if pending_heading:
                return f"{pending_heading}\n\n{stripped}"
            return stripped
        # Not meaningful — reset pending heading (it led to noise, not content).
        pending_heading = None

    # No meaningful paragraph found — fall back to full body.
    return text


def _is_meaningful(paragraph: str) -> bool:
    """Return True if *paragraph* looks like human-readable text.

    Detects meaningful content by checking for a minimum density of Unicode
    letters and spaces — markers of natural language in any script.
    Headings, fenced code blocks, and HTML comments are skipped.
    """
    stripped = paragraph.strip()
    if not stripped:
        return False
    # Headings — caller handles them separately.
    if stripped.startswith("#"):
        return False
    # Fenced code blocks.
    if stripped.startswith("```"):
        return False
    # HTML / markdown comments.
    if stripped.startswith("<!--"):
        return False
    # Count characters that indicate natural language.
    text_chars = sum(1 for c in stripped if c.isalpha() or c == " ")
    return text_chars >= 4 and text_chars / len(stripped) > 0.3


def _extract_compact_text(content: str) -> str | None:
    """Return text under the ## Compact heading, or None."""
    in_compact = False
    result_lines: list[str] = []
    for line in content.splitlines():
        if line.strip() == "## Compact":
            in_compact = True
            continue
        if in_compact:
            if line.startswith("##"):
                break
            result_lines.append(line)
    text = "\n".join(result_lines).strip()
    return text if text else None


def _all_session_dirs(sessions_base: Path) -> list[Path]:
    """Return all directories that may contain session files.

    Covers both sessions/ and projects/*/sessions/.
    """
    dirs: list[Path] = []
    top = sessions_base / "sessions"
    if top.exists():
        dirs.append(top)
    projects = sessions_base / "projects"
    if projects.exists():
        for p in projects.iterdir():
            sd = p / "sessions"
            if sd.is_dir():
                dirs.append(sd)
    return dirs


# ---------------------------------------------------------------------------
# Tag exploration
# ---------------------------------------------------------------------------


def explore_tags() -> dict:
    """Return a count of files per tag across the entire storage tree.

    Uses SQLite index when populated, falls back to filesystem scan.

    Returns:
        Dict with key 'tags': list of {'name': str, 'count': int}
        sorted alphabetically by name.
    """
    from .db import is_populated

    if is_populated():
        from .db import get_connection
        conn = get_connection()
        rows = conn.execute(
            "SELECT tag, COUNT(*) AS cnt FROM file_tags GROUP BY tag ORDER BY tag"
        ).fetchall()
        return {"tags": [{"name": r[0], "count": r[1]} for r in rows]}

    # Filesystem fallback
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
# Aggregate statistics
# ---------------------------------------------------------------------------


def get_stats() -> dict:
    """Return aggregate statistics about stored memory files.

    Uses SQLite index when populated for O(1) counts; falls back to tag
    exploration via filesystem scan.

    Returns:
        Dict with keys:
          data_dir (str), sessions_dir (str), data_dir_size_mb (float),
          total_facts (int), total_sessions (int), total_tags (int),
          projects (list[{name, facts, sessions}] sorted by total desc),
          top_tags (list[{name, count}] — up to 20, sorted by count desc).
    """
    base = get_base_dir()
    sessions_base = get_sessions_base_dir()

    def _dir_size_mb(p: Path) -> float:
        try:
            return sum(f.stat().st_size for f in p.rglob("*") if f.is_file()) / (1024 * 1024)
        except OSError:
            return 0.0

    stats: dict = {
        "data_dir": str(base),
        "sessions_dir": str(sessions_base),
        "data_dir_size_mb": round(_dir_size_mb(base), 2),
        "total_facts": 0,
        "total_sessions": 0,
        "total_tags": 0,
        "projects": [],
        "top_tags": [],
    }

    from .db import is_populated

    if is_populated():
        from .db import get_connection
        conn = get_connection()

        # Session count and non-session (fact) count via index
        try:
            row = conn.execute(
                "SELECT COUNT(*) FROM file_tags WHERE tag = 'session'"
            ).fetchone()
            stats["total_sessions"] = row[0] if row else 0

            row = conn.execute(
                "SELECT COUNT(*) FROM files f WHERE NOT EXISTS "
                "(SELECT 1 FROM file_tags WHERE rel_path = f.rel_path AND tag = 'session')"
            ).fetchone()
            stats["total_facts"] = row[0] if row else 0
        except Exception:
            pass

        # Per-project breakdown
        try:
            rows = conn.execute(
                "SELECT DISTINCT tag FROM file_tags WHERE tag LIKE 'project/%' ORDER BY tag"
            ).fetchall()
            for (tag,) in rows:
                project_name = tag[len("project/"):]
                fr = conn.execute(
                    "SELECT COUNT(*) FROM file_tags f1 WHERE f1.tag = ? "
                    "AND NOT EXISTS (SELECT 1 FROM file_tags f2 "
                    "WHERE f2.rel_path = f1.rel_path AND f2.tag = 'session')",
                    (tag,),
                ).fetchone()
                sr = conn.execute(
                    "SELECT COUNT(*) FROM file_tags f1 WHERE f1.tag = ? "
                    "AND EXISTS (SELECT 1 FROM file_tags f2 "
                    "WHERE f2.rel_path = f1.rel_path AND f2.tag = 'session')",
                    (tag,),
                ).fetchone()
                stats["projects"].append({
                    "name": project_name,
                    "facts": fr[0] if fr else 0,
                    "sessions": sr[0] if sr else 0,
                })
            stats["projects"].sort(
                key=lambda p: p["facts"] + p["sessions"], reverse=True
            )
        except Exception:
            pass

    # Tags (works from SQL index or filesystem fallback via explore_tags)
    tag_data = explore_tags()
    all_tags = tag_data.get("tags", [])
    all_tags_sorted = sorted(all_tags, key=lambda t: t["count"], reverse=True)
    stats["total_tags"] = len(all_tags)
    stats["top_tags"] = all_tags_sorted[:20]

    return stats


# ---------------------------------------------------------------------------
# Tag resolution
# ---------------------------------------------------------------------------


def resolve_tags(query_tags: list[str]) -> list[str]:
    """Normalize approximate tag names to existing tags.

    Resolution strategy (priority order):
      1. Exact match — always accepted.
      2. Vector similarity (cosine) — used when OPENAI_API_KEY is set;
         only matches with score ≥ 0.88 are accepted.
      3. No match — query tag is omitted from results.

    Fuzzy substring matching was removed: it produced false positives on
    short tag names (e.g. "rule" matching "preference" via overlap).
    Vector similarity is the only reliable fuzzy method.

    Args:
        query_tags: list of approximate or abbreviated tag names

    Returns:
        Ordered, deduplicated list of resolved existing tags.
        Query tags with no match are excluded — callers must re-merge
        the originals if they want to preserve unmatched tags.
    """
    from .vector_store import tag_store
    from .db import is_populated

    all_tags: set[str] = set()
    if is_populated():
        from .db import get_connection
        conn = get_connection()
        rows = conn.execute("SELECT DISTINCT tag FROM file_tags").fetchall()
        all_tags = {r[0] for r in rows}
    else:
        # Filesystem fallback
        base = get_base_dir()
        for md_file in base.rglob("*.md"):
            content = _read_content(md_file)
            if content is None:
                continue
            all_tags.update(all_tags_for_file(md_file, base, content))

    resolved: list[str] = []
    seen: set[str] = set()

    if tag_store.enabled:
        similar = tag_store.find_similar(query_tags, all_tags)
        for q in query_tags:
            match = similar.get(q)
            if match and match not in seen:
                seen.add(match)
                resolved.append(match)
    else:
        # Vectorization off — exact matches only, no fuzzy dedup
        for q in query_tags:
            if q in all_tags and q not in seen:
                seen.add(q)
                resolved.append(q)

    return resolved
