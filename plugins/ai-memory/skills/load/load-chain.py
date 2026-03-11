#!/usr/bin/env python3
# @ai-generated(solo)
# Load skill helper: discovers session chain and outputs combined context.
#
# Usage:
#   python3 load-chain.py <session-id> [project]  # traverse continuation chain
#   python3 load-chain.py --file <rel-path>        # load specific session by path
#
# Chain mode: finds session summary.md by id field, then follows continues:
# Obsidian wiki-links to build the full chain.
#
# Content strategy: shows messages.md (compact) for the most recent session.
# Older sessions in chain: just title + summary line.
# If no session found by id: shows CHOOSE_SESSION list of recent sessions.
#
# File paths in output use [file: rel-path] markers that /load SKILL.md parses.

import re
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


def _parse_wikilink(value: str) -> str | None:
    """Extract the page name from [[wiki-link]], or None if not a wiki-link."""
    m = re.match(r"^\[\[(.+)\]\]$", value.strip())
    return m.group(1) if m else None


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
        for f in sorted(d.glob("*.md"), reverse=True):  # newest first for speed
            if storage._is_messages_file(f.name):
                continue
            content = storage._read_content(f)
            if content and parse_front_matter(content).get("id") == session_id:
                return f
    return None


def _find_session_by_wikilink(ref: str, sessions_dir: Path) -> Path | None:
    """Resolve an Obsidian wiki-link ref to a summary.md in the same directory.

    Tries exact filename match first, then case-insensitive fallback.

    Args:
        ref: the page name extracted from [[ref]]
        sessions_dir: directory to search in

    Returns:
        Path to summary.md, or None if not found.
    """
    candidate = sessions_dir / f"{ref}.md"
    if candidate.exists():
        return candidate
    ref_lower = ref.lower()
    for f in sessions_dir.glob("*.md"):
        if not storage._is_messages_file(f.name) and f.stem.lower() == ref_lower:
            return f
    return None


def _read_messages(summary_path: Path) -> str | None:
    """Read the paired .messages.md file for a session summary, or None."""
    messages_path = summary_path.parent / f"{summary_path.stem}.messages.md"
    return storage._read_content(messages_path)


def _get_title(summary_path: Path) -> str:
    """Extract title from summary.md front-matter, falling back to stem."""
    content = storage._read_content(summary_path) or ""
    return parse_front_matter(content).get("title", summary_path.stem)


def _get_summary_line(summary_path: Path) -> str:
    """Extract the first line of ## Summary from a summary.md file."""
    content = storage._read_content(summary_path) or ""
    return storage._extract_summary_text(content) or "(no summary)"


def _print_session_content(summary_path: Path) -> None:
    """Print session content for /load recovery.

    Priority:
    1. Summary file with ## Compact section (agent ran /save — most useful)
    2. Full conversation transcript from messages.md (auto-saved by Stop hook)
    3. Summary file alone (short summary, last resort)
    """
    content = storage._read_content(summary_path) or ""
    if "## Compact" in content:
        # Compact notes written by /save — best recovery source
        print(content)
    else:
        messages = _read_messages(summary_path)
        if messages:
            print(messages)
        else:
            print(content)


def _traverse_chain(start: Path) -> list[Path]:
    """Follow continues: wiki-links starting from start, newest first.

    Args:
        start: Path to the most recent session summary.md

    Returns:
        List of session summary.md paths, newest first.
        Stops after 10 hops or when continues: is absent/unresolvable.
    """
    chain = [start]
    seen = {start.resolve()}
    current = start

    for _ in range(10):  # guard against cycles or excessively long chains
        content = storage._read_content(current) or ""
        fm = parse_front_matter(content)
        continues = fm.get("continues", "").strip()
        if not continues:
            break
        ref = _parse_wikilink(continues)
        if not ref:
            break
        prev = _find_session_by_wikilink(ref, current.parent)
        if prev is None or prev.resolve() in seen:
            break
        seen.add(prev.resolve())
        chain.append(prev)
        current = prev

    return chain


# ---------------------------------------------------------------------------
# Prev-session cache helpers
# ---------------------------------------------------------------------------


def _read_prev_session_cache(project: str | None) -> str | None:
    """Read the prev-session cache written by session-end.py hook.

    The hook writes ~/.claude/hooks/state/prev-session-{project}.json on
    every clear event.  We use it to bootstrap the chain for new sessions
    that haven't been saved yet (so _find_session_by_id returns None).

    Args:
        project: project name; if None, no cache file to read.

    Returns:
        Previous session ID string, or None if not found/unreadable.
    """
    if not project:
        return None
    cache_path = Path.home() / ".claude" / "hooks" / "state" / f"prev-session-{project}.json"
    try:
        import json
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
        print("       python3 load-chain.py --file <rel-path>")
        sys.exit(1)

    if args[0] == "--file":
        if len(args) < 2:
            print("Usage: python3 load-chain.py --file <rel-path>")
            sys.exit(1)
        # Try sessions_base first (handles AI_MEMORY_SESSIONS_DIR override),
        # then fall back to base_dir for backwards compatibility.
        sessions_base = storage.get_sessions_base_dir()
        base = storage.get_base_dir()
        summary_path = sessions_base / args[1]
        if not summary_path.exists() and sessions_base != base:
            summary_path = base / args[1]
        if not summary_path.exists():
            print(f"Session file not found: {args[1]}")
            sys.exit(1)
        print("# Session Recovery")
        print()
        _print_session_content(summary_path)
        return

    session_id = args[0]
    project = args[1] if len(args) > 1 else None
    sid_prefix = session_id[:8]

    start = _find_session_by_id(session_id, project)

    if start:
        chain = _traverse_chain(start)
        latest = chain[0]
        older = chain[1:]

        print("# Session Chain Recovery")
        print()
        print(f"{len(chain)} previous session(s) in chain.")

        for prev in older:
            title = _get_title(prev)
            summary = _get_summary_line(prev)
            print()
            print("---")
            print(f"## {title} — {summary}")

        print()
        print("---")
        print(f"## {_get_title(latest)}")
        print()
        _print_session_content(latest)
        print()
        print("---")

    else:
        # Current session not in storage yet (not saved) — try prev-session cache
        # written by session-end.py hook so we can still traverse the chain.
        prev_id = _read_prev_session_cache(project)
        if prev_id and prev_id[:8] != sid_prefix:
            start = _find_session_by_id(prev_id, project)

        if start:
            chain = _traverse_chain(start)
            latest = chain[0]
            older = chain[1:]

            print("# Session Chain Recovery")
            print()
            print(f"{len(chain)} previous session(s) in chain.")

            for prev in older:
                title = _get_title(prev)
                summary = _get_summary_line(prev)
                print()
                print("---")
                print(f"## {title} — {summary}")

            print()
            print("---")
            print(f"## {_get_title(latest)}")
            print()
            _print_session_content(latest)
            print()
            print("---")
        else:
            # No chain found — show recent candidates for user to pick
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
