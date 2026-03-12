#!/usr/bin/env python3
# @ai-generated(solo)
# Load skill helper: finds previous session and outputs its content.
#
# Usage:
#   python3 load-chain.py <session-id> [project]  # find prev session via cache
#   python3 load-chain.py --ref <stem>             # load session by wikilink stem
#   python3 load-chain.py --file <rel-path>        # load specific session by path
#   python3 load-chain.py --blob <blob-dir>        # load session by blob dir name
#
# Session linking relies on prev-session cache written by session-end.py hook
# (stored in SQLite state table, keyed by project name).
#
# Content strategy: prints summary + compact (if present), then tail of
# the ## Transcript section (~2000 chars, or ~500 if Compact exists).

import sys
from pathlib import Path

# Allow importing from the shared lib/ package regardless of cwd
_PLUGIN_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(_PLUGIN_ROOT))

from lib import storage  # noqa: E402
from lib.tags import parse_front_matter  # noqa: E402


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _find_by_stem(stem: str) -> Path | None:
    """Find a .md file by its wikilink stem. Delegates to storage.find_file_by_stem."""
    return storage.find_file_by_stem(stem)


def _find_session_by_id(session_id: str, project: str | None) -> Path | None:
    """Scan sessions directories for a summary.md with a matching id field.

    Searches project sessions dir first (if given), then the generic sessions dir.

    Args:
        session_id: full session UUID to match against front-matter id:
        project: optional project name for targeted search

    Returns:
        Path to summary.md, or None if not found.
    """
    sessions_base = storage.get_sessions_base_dir()
    search_dirs: list[Path] = []
    if project:
        search_dirs.append(sessions_base / "projects" / project / "sessions")
    search_dirs.append(sessions_base / "sessions")

    seen: set[Path] = set()
    for d in search_dirs:
        if not d.exists() or d in seen:
            continue
        seen.add(d)
        for f in sorted(d.rglob("*.md"), reverse=True):  # newest first for speed
            if storage._is_messages_file(f.name):
                continue
            content = storage._read_content(f)
            if content and parse_front_matter(content).get("id") == session_id:
                return f
    return None


def _get_title(summary_path: Path) -> str:
    """Extract title from summary.md front-matter, falling back to stem."""
    content = storage._read_content(summary_path) or ""
    return parse_front_matter(content).get("title", summary_path.stem)


def _extract_section(content: str, heading: str) -> str | None:
    """Extract body text under a markdown heading, up to the next same-level heading.

    Also stops at a ``---`` divider so that ## Summary / ## Compact content
    doesn't bleed into the ## Transcript block (separated by ``---``).

    Args:
        content: full markdown text
        heading: heading line to find, e.g. '## Compact'

    Returns:
        Section body text (without the heading itself), or None if not found.
    """
    level = len(heading) - len(heading.lstrip("#"))
    prefix = "#" * level + " "
    lines = content.splitlines()
    start = None
    for i, line in enumerate(lines):
        if line.strip() == heading.strip():
            start = i + 1
        elif start is not None and (line.startswith(prefix) or line.strip() == "---"):
            return "\n".join(lines[start:i]).strip() or None
    if start is not None:
        return "\n".join(lines[start:]).strip() or None
    return None


def _print_session_content(summary_path: Path) -> None:
    """Print session content: summary + compact + transcript tail.

    Transcript is stored as ``## Transcript`` section in the same file
    (below a ``---`` divider). Only the tail is printed to keep output
    manageable — budget depends on whether Compact section exists.

    Args:
        summary_path: path to the session summary .md file.
    """
    content = storage._read_content(summary_path) or ""
    has_compact = "## Compact" in content

    # Print everything up to the --- divider before transcript
    divider_marker = "\n---\n\n## Transcript\n"
    divider_idx = content.find(divider_marker)
    if divider_idx != -1:
        print(content[:divider_idx].rstrip())
    else:
        print(content)
        return  # no transcript section

    # Print tail of transcript
    transcript = _extract_section(content, "## Transcript")
    if transcript:
        tail_budget = 500 if has_compact else 2000
        tail = transcript[-tail_budget:]
        # Avoid cutting mid-line
        newline = tail.find("\n")
        if newline != -1 and newline < len(tail) - 1:
            tail = tail[newline + 1:]
        if tail.strip():
            print()
            print("## Recent messages")
            print()
            print(tail)


def _print_recovery(summary_path: Path) -> None:
    """Print full session recovery output."""
    print("# Session Recovery")
    print()
    print(f"*git: {_get_title(summary_path)}*")
    print()
    _print_session_content(summary_path)


# ---------------------------------------------------------------------------
# Prev-session cache
# ---------------------------------------------------------------------------


def _read_prev_session_cache(project: str | None) -> str | None:
    """Read the prev-session cache written by session-end.py hook.

    The hook saves {session_id, project, timestamp} to SQLite state on every
    /clear event.  This is the only mechanism for linking sessions — it bridges
    the gap between the old session (already ended) and the new one (not yet saved).

    Args:
        project: project name; if None, no cache to read.

    Returns:
        Previous session ID string, or None if not found/unreadable.
    """
    if not project:
        return None
    import json
    # Try SQLite state first
    try:
        from lib.db import get_state
        raw = get_state(f"prev-session-{project}")
        if raw:
            return json.loads(raw).get("session_id")
    except Exception:
        pass
    # Fallback: legacy JSON file
    cache_path = Path.home() / ".claude" / "hooks" / "state" / f"prev-session-{project}.json"
    try:
        data = json.loads(cache_path.read_text(encoding="utf-8"))
        return data.get("session_id")
    except (OSError, ValueError, KeyError):
        return None


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    args = sys.argv[1:]

    if not args:
        print("Usage: python3 load-chain.py <session-id> [project]")
        print("       python3 load-chain.py --ref <stem>")
        print("       python3 load-chain.py --file <rel-path>")
        print("       python3 load-chain.py --blob <blob-dir>")
        sys.exit(1)

    if args[0] == "--ref":
        if len(args) < 2:
            print("Usage: python3 load-chain.py --ref <stem>")
            sys.exit(1)
        # Strip [[ ]] if the agent passes the full wikilink syntax
        stem = args[1].strip("[]")
        found = _find_by_stem(stem)
        if not found:
            print(f"Session not found for ref: [[{stem}]]")
            sys.exit(1)
        # Agent already has front-matter + Summary from memory_search
        # (truncated to first paragraph). Print Compact + transcript tail.
        content = storage._read_content(found) or ""
        has_compact = "## Compact" in content
        if has_compact:
            compact = _extract_section(content, "## Compact")
            if compact:
                print("## Compact")
                print()
                print(compact)
        transcript = _extract_section(content, "## Transcript")
        if transcript:
            tail_budget = 500 if has_compact else 2000
            tail = transcript[-tail_budget:]
            newline = tail.find("\n")
            if newline != -1 and newline < len(tail) - 1:
                tail = tail[newline + 1:]
            if tail.strip():
                print()
                print("## Recent messages")
                print()
                print(tail)
        return

    if args[0] == "--file":
        if len(args) < 2:
            print("Usage: python3 load-chain.py --file <rel-path>")
            sys.exit(1)
        sessions_base = storage.get_sessions_base_dir()
        base = storage.get_base_dir()
        summary_path = sessions_base / args[1]
        if not summary_path.exists() and sessions_base != base:
            summary_path = base / args[1]
        if not summary_path.exists():
            print(f"Session file not found: {args[1]}")
            sys.exit(1)
        _print_recovery(summary_path)
        return

    if args[0] == "--blob":
        if len(args) < 2:
            print("Usage: python3 load-chain.py --blob <blob-dir>")
            sys.exit(1)
        # blob-dir is a stem like "2026-03-12 title.sid8" — search for it
        blob_dir = args[1]
        sessions_base = storage.get_sessions_base_dir()
        # Search all session directories for a file matching the blob dir
        for d in sessions_base.rglob("sessions"):
            if not d.is_dir():
                continue
            candidate = d / f"{blob_dir}.md"
            if candidate.exists():
                _print_recovery(candidate)
                return
            # Try glob match for partial stem
            for f in d.glob(f"*{blob_dir}*"):
                if f.is_file() and not storage._is_messages_file(f.name):
                    _print_recovery(f)
                    return
        print(f"Session not found for blob: {blob_dir}")
        sys.exit(1)

    session_id = args[0]
    project = args[1] if len(args) > 1 else None
    sid_prefix = session_id[:8]

    # Try to find the current session in storage (already saved via memory_session)
    start = _find_session_by_id(session_id, project)

    if not start:
        # Current session not saved yet — try prev-session cache from session-end hook
        prev_id = _read_prev_session_cache(project)
        if prev_id and prev_id[:8] != sid_prefix:
            start = _find_session_by_id(prev_id, project)

    if start:
        _print_recovery(start)
    else:
        # No link found — show recent candidates for user to pick
        sessions = storage.search_sessions(project=project, limit=8)
        candidates = [s for s in sessions if s.get("id", "")[:8] != sid_prefix]

        if candidates:
            print("# CHOOSE_SESSION")
            print()
            print("No continuation chain found. Recent sessions:")
            print()
            for i, s in enumerate(candidates):
                title = s.get("title", "(untitled)")
                summary = s.get("summary", "")
                path = s.get("path", "")
                line = f"{i + 1}. **{title}**"
                if summary:
                    line += f" — {summary}"
                line += f" `[file: {path}]`"
                print(line)
        else:
            print("No previous session found.")


if __name__ == "__main__":
    main()
