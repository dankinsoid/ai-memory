#!/usr/bin/env python3
# @ai-generated(solo)
"""ai-memory CLI — debug, observe, and manage your memory store.

Commands:
  status          Show cache DB location, size, and index stats.
  health          Run integrity checks on the DB and filesystem.
  stats           Show aggregate memory statistics by project and tag.
  search          Search facts and sessions by text or tags.
  list            List facts, sessions, tags, or projects.
  show            Display a memory file by its [[ref]] stem.
  edit            Open a memory file in $EDITOR and re-index it.
  delete          Remove a memory file from disk and index.
  reindex         Rebuild the file index and re-embed all content.
  clear-cache     Delete the SQLite index/cache DB (data files intact).
  export-vectors  Export all vectors to a JSON file.
  import-vectors  Import vectors from a JSON file.

Run without arguments to enter the interactive shell.

Usage:
  python3 cli.py
  python3 cli.py status
  python3 cli.py health
  python3 cli.py stats
  python3 cli.py search [query] [--tags t1,t2] [--project P] [--type facts|sessions|all]
  python3 cli.py list facts|sessions|tags|projects [options]
  python3 cli.py show <ref>
  python3 cli.py edit <ref>
  python3 cli.py delete <ref> [--yes]
  python3 cli.py reindex [--force]
  python3 cli.py clear-cache
  python3 cli.py export-vectors [-o vectors.json]
  python3 cli.py import-vectors vectors.json [--replace]
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import struct
import subprocess
import sys
from pathlib import Path

# Ensure lib/ is importable from this file's directory
sys.path.insert(0, str(Path(__file__).resolve().parent))


# ---------------------------------------------------------------------------
# Auto-load AI_MEMORY_DIR from ~/.claude/settings.json when not set in env
# ---------------------------------------------------------------------------

def _load_env_from_claude_settings() -> None:
    """Populate AI_MEMORY_DIR from Claude settings.

    Claude Code stores plugin env vars in ~/.claude/settings.json under an
    'env' key.  When the CLI is run directly from the terminal (not via a
    Claude Code hook), these vars are absent, causing storage.get_base_dir()
    to fall back to ~/.claude/ai-memory/ instead of the configured path.

    This function reads the settings file once at startup and sets any missing
    AI_MEMORY_* env vars so the rest of the code sees the right paths.
    """
    if os.environ.get("AI_MEMORY_DIR"):
        return  # already set — nothing to do

    settings_path = Path.home() / ".claude" / "settings.json"
    if not settings_path.exists():
        return

    try:
        data = json.loads(settings_path.read_text(encoding="utf-8"))
        env = data.get("env", {})
        for key in ("AI_MEMORY_DIR",):
            if key in env and key not in os.environ:
                # Expand ~ in paths
                os.environ[key] = str(Path(env[key]).expanduser())
    except Exception:
        pass  # Malformed settings — silently skip; defaults will be used


_load_env_from_claude_settings()


# ---------------------------------------------------------------------------
# Optional rich — graceful fallback to plain text
# ---------------------------------------------------------------------------

try:
    from rich.console import Console
    from rich.table import Table
    from rich.panel import Panel
    from rich.markdown import Markdown
    from rich.progress import Progress, SpinnerColumn, TextColumn, BarColumn
    from rich.text import Text
    from rich import box as rich_box
    _RICH = True
    _console = Console()
    _err_console = Console(stderr=True)
except ImportError:
    _RICH = False
    _console = None  # type: ignore[assignment]
    _err_console = None  # type: ignore[assignment]


# ---------------------------------------------------------------------------
# Optional prompt_toolkit — graceful fallback to input()
# ---------------------------------------------------------------------------

try:
    from prompt_toolkit import PromptSession
    from prompt_toolkit.history import FileHistory
    from prompt_toolkit.completion import WordCompleter
    from prompt_toolkit.styles import Style as PtStyle
    from prompt_toolkit.formatted_text import HTML
    _PROMPT_TOOLKIT = True
except ImportError:
    _PROMPT_TOOLKIT = False


# ---------------------------------------------------------------------------
# Display helpers
# ---------------------------------------------------------------------------

def _print(text: object = "", style: str | None = None) -> None:
    """Print to stdout via rich (with optional style) or plain print."""
    if _RICH:
        _console.print(text, style=style)
    else:
        print(text)


def _err(text: str) -> None:
    """Print an error message to stderr."""
    if _RICH:
        _err_console.print(f"[bold red]Error:[/bold red] {text}")
    else:
        print(f"Error: {text}", file=sys.stderr)


def _ok(text: str) -> None:
    """Print a success message."""
    _print(text, style="bold green" if _RICH else None)


def _warn(text: str) -> None:
    """Print a warning message."""
    _print(text, style="bold yellow" if _RICH else None)


def _status_icon(ok: bool, warn: bool = False) -> str:
    """Return a ✓/⚠/✗ icon based on condition."""
    if ok:
        return "[green]✓[/green]" if _RICH else "✓"
    if warn:
        return "[yellow]⚠[/yellow]" if _RICH else "⚠"
    return "[red]✗[/red]" if _RICH else "✗"


def _truncate(text: str, max_len: int = 80) -> str:
    """Return text truncated at max_len with ellipsis."""
    text = text.replace("\n", " ").strip()
    if len(text) <= max_len:
        return text
    return text[:max_len - 1] + "…"


def _preview(content: str, max_len: int = 80) -> str:
    """Extract first meaningful line from markdown content for preview."""
    for line in content.splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("---") and not stripped.startswith("#"):
            return _truncate(stripped, max_len)
    return ""


# ---------------------------------------------------------------------------
# DB path helper (needed before lib is imported in clear-cache)
# ---------------------------------------------------------------------------

def _db_path() -> Path:
    """Return platform-appropriate cache path for the index DB."""
    if sys.platform == "darwin":
        cache = Path.home() / "Library" / "Caches" / "ai-memory"
    else:
        xdg = os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache"))
        cache = Path(xdg) / "ai-memory"
    return cache / "index.db"


# ---------------------------------------------------------------------------
# Command: status
# ---------------------------------------------------------------------------

def cmd_status(args: argparse.Namespace) -> None:
    """Show cache DB location, size, and index stats."""
    db = _db_path()

    if _RICH:
        t = Table(title="ai-memory Status", box=rich_box.ROUNDED, show_header=True)
        t.add_column("Item", style="bold cyan")
        t.add_column("Value")

        t.add_row("Cache DB", str(db))
        if not db.exists():
            t.add_row("DB status", "[yellow]not created yet[/yellow]")
            _console.print(t)
            return

        size_mb = db.stat().st_size / (1024 * 1024)
        t.add_row("DB size", f"{size_mb:.2f} MB")

        import sqlite3
        conn = sqlite3.connect(str(db), timeout=5.0)

        try:
            row = conn.execute("SELECT COUNT(*) FROM files").fetchone()
            t.add_row("Indexed files", str(row[0]))
        except sqlite3.OperationalError:
            t.add_row("Indexed files", "[yellow]table missing[/yellow]")

        try:
            rows = conn.execute(
                "SELECT collection, COUNT(*) FROM vectors GROUP BY collection ORDER BY collection"
            ).fetchall()
            if rows:
                t.add_row("Vector collections", "")
                for collection, count in rows:
                    t.add_row(f"  {collection}", f"{count} vectors")
            else:
                t.add_row("Vector collections", "[dim]empty[/dim]")
        except sqlite3.OperationalError:
            t.add_row("Vector collections", "[yellow]table missing[/yellow]")

        try:
            row = conn.execute("SELECT COUNT(*) FROM state").fetchone()
            t.add_row("State entries", str(row[0]))
        except sqlite3.OperationalError:
            pass

        conn.close()

        from lib.storage import get_base_dir
        base = get_base_dir()
        md_count = sum(1 for _ in base.rglob("*.md"))
        t.add_row("Data dir", str(base))
        t.add_row("Markdown files", str(md_count))

        _console.print(t)
    else:
        # Plain-text fallback
        print(f"Cache DB: {db}")
        if not db.exists():
            print("  (not created yet)")
            return

        size_mb = db.stat().st_size / (1024 * 1024)
        print(f"  Size: {size_mb:.2f} MB")

        import sqlite3
        conn = sqlite3.connect(str(db), timeout=5.0)
        try:
            row = conn.execute("SELECT COUNT(*) FROM files").fetchone()
            print(f"  Indexed files: {row[0]}")
        except sqlite3.OperationalError:
            print("  Indexed files: (table missing)")
        try:
            rows = conn.execute(
                "SELECT collection, COUNT(*) FROM vectors GROUP BY collection"
            ).fetchall()
            for collection, count in rows:
                print(f"  {collection}: {count} vectors")
        except sqlite3.OperationalError:
            pass
        conn.close()

        from lib.storage import get_base_dir
        base = get_base_dir()
        md_count = sum(1 for _ in base.rglob("*.md"))
        print(f"Data dir: {base}")
        print(f"  Files: {md_count} .md files")


# ---------------------------------------------------------------------------
# Command: health
# ---------------------------------------------------------------------------

def cmd_health(args: argparse.Namespace) -> None:
    """Run integrity checks on the DB and compare with filesystem."""
    from lib.storage import get_base_dir
    from lib.db import health_check

    base = get_base_dir()

    if _RICH:
        with Progress(SpinnerColumn(), TextColumn("{task.description}"),
                      transient=True, console=_console) as p:
            p.add_task("Running health checks…")
            result = health_check(base)
    else:
        print("Running health checks…")
        result = health_check(base)

    if _RICH:
        t = Table(title="Health Check", box=rich_box.ROUNDED)
        t.add_column("Check", style="bold cyan", no_wrap=True)
        t.add_column("Status")
        t.add_column("Detail")

        # DB exists
        db_ok = result["db_exists"]
        t.add_row("Cache DB exists", _status_icon(db_ok),
                  result["db_path"])

        if not db_ok:
            t.add_row("(DB not created)", "[dim]—[/dim]",
                      "Run reindex or use memory tools to create it")
            _console.print(t)
            return

        # DB size
        t.add_row("DB size", "[dim]info[/dim]",
                  f"{result['db_size_mb']:.2f} MB")

        # Integrity
        integrity = result["db_integrity"]
        int_ok = integrity == "ok"
        t.add_row("DB integrity", _status_icon(int_ok),
                  integrity or "unknown")

        # Index sync
        indexed = result["indexed_count"]
        fs_count = result["fs_count"]
        diff = abs(indexed - fs_count)
        sync_ok = diff == 0
        sync_warn = 0 < diff <= 5
        t.add_row(
            "Index sync",
            _status_icon(sync_ok, warn=sync_warn),
            f"{indexed} indexed / {fs_count} on disk"
            + (f" ([yellow]{diff} drift[/yellow])" if not sync_ok else ""),
        )

        # Orphans
        orphan_ok = result["orphan_count"] == 0
        orphan_detail = (
            "none" if orphan_ok
            else f"{result['orphan_count']} entries without files on disk"
        )
        t.add_row("Orphan entries", _status_icon(orphan_ok, warn=not orphan_ok),
                  orphan_detail)

        # State entries
        t.add_row("State entries", "[dim]info[/dim]",
                  str(result["state_count"]))

        # Vector collections
        vc = result["vector_collections"]
        if vc:
            t.add_row("Vector collections", "[dim]info[/dim]",
                      ", ".join(f"{k}: {v}" for k, v in vc.items()))
        else:
            t.add_row("Vector collections", "[dim]info[/dim]",
                      "[dim]empty (semantic search disabled or not indexed)[/dim]")

        _console.print(t)

        if result["orphan_paths"]:
            _console.print("\n[bold yellow]Orphan paths (indexed but missing on disk):[/bold yellow]")
            for p in result["orphan_paths"]:
                _console.print(f"  [dim]{p}[/dim]")

    else:
        result = health_check(base)
        ok = "OK" if result["db_exists"] else "MISSING"
        print(f"DB exists:     {ok}  ({result['db_path']})")
        if result["db_exists"]:
            print(f"DB integrity:  {result['db_integrity']}")
            print(f"DB size:       {result['db_size_mb']:.2f} MB")
            print(f"Indexed files: {result['indexed_count']}")
            print(f"FS files:      {result['fs_count']}")
            print(f"Orphans:       {result['orphan_count']}")
            print(f"State entries: {result['state_count']}")
            if result["vector_collections"]:
                for k, v in result["vector_collections"].items():
                    print(f"Vectors [{k}]:  {v}")


# ---------------------------------------------------------------------------
# Command: stats
# ---------------------------------------------------------------------------

def cmd_stats(args: argparse.Namespace) -> None:
    """Show aggregate memory statistics."""
    from lib.storage import get_stats

    if _RICH:
        with Progress(SpinnerColumn(), TextColumn("{task.description}"),
                      transient=True, console=_console) as p:
            p.add_task("Gathering stats…")
            stats = get_stats()
    else:
        print("Gathering stats…")
        stats = get_stats()

    if _RICH:
        # Overview panel
        overview = Table.grid(padding=(0, 2))
        overview.add_column(style="bold cyan")
        overview.add_column()
        overview.add_row("Facts / rules", str(stats["total_facts"]))
        overview.add_row("Sessions", str(stats["total_sessions"]))
        overview.add_row("Unique tags", str(stats["total_tags"]))
        overview.add_row("Data dir", stats["data_dir"])
        overview.add_row("Data dir size", f"{stats['data_dir_size_mb']:.2f} MB")
        _console.print(Panel(overview, title="Overview", border_style="cyan",
                             box=rich_box.ROUNDED))

        # Projects table
        if stats["projects"]:
            pt = Table(title="Projects", box=rich_box.SIMPLE)
            pt.add_column("Project", style="bold")
            pt.add_column("Facts", justify="right")
            pt.add_column("Sessions", justify="right")
            pt.add_column("Total", justify="right", style="bold")
            for p in stats["projects"]:
                pt.add_row(
                    p["name"],
                    str(p["facts"]),
                    str(p["sessions"]),
                    str(p["facts"] + p["sessions"]),
                )
            _console.print(pt)

        # Top tags table
        if stats["top_tags"]:
            tt = Table(title="Top Tags (by file count)", box=rich_box.SIMPLE)
            tt.add_column("Tag", style="bold cyan")
            tt.add_column("Files", justify="right")
            for tag in stats["top_tags"]:
                tt.add_row(tag["name"], str(tag["count"]))
            _console.print(tt)
    else:
        print(f"Facts:   {stats['total_facts']}")
        print(f"Sessions: {stats['total_sessions']}")
        print(f"Tags:    {stats['total_tags']}")
        print(f"Data dir: {stats['data_dir']} ({stats['data_dir_size_mb']:.2f} MB)")
        if stats["projects"]:
            print("\nProjects:")
            for p in stats["projects"]:
                print(f"  {p['name']}: {p['facts']} facts, {p['sessions']} sessions")
        if stats["top_tags"]:
            print("\nTop tags:")
            for t in stats["top_tags"]:
                print(f"  {t['name']}: {t['count']}")


# ---------------------------------------------------------------------------
# Command: search
# ---------------------------------------------------------------------------

def cmd_search(args: argparse.Namespace) -> None:
    """Search facts and sessions by text query or tags."""
    from lib.storage import search_facts, search_sessions

    query = args.query or None
    tags = [t.strip() for t in args.tags.split(",")] if args.tags else None
    any_tags = [t.strip() for t in args.any_tags.split(",")] if args.any_tags else None
    project = getattr(args, "project", None)
    search_type = getattr(args, "type", "all")
    limit = getattr(args, "limit", 20)

    results: list[dict] = []

    if search_type in ("all", "facts"):
        scope_tags = list(tags or [])
        if project:
            scope_tags.append(f"project/{project}")
        results += search_facts(
            tags=scope_tags or None,
            any_tags=any_tags,
            exclude_tags=["session"],
            query=query,
            since=getattr(args, "since", None),
            until=getattr(args, "until", None),
            limit=limit,
        )

    if search_type in ("all", "sessions"):
        if project or (search_type == "sessions"):
            sessions = search_sessions(
                project=project,
                text=query,
                since=getattr(args, "since", None),
                until=getattr(args, "until", None),
                limit=limit,
            )
            # Adapt session records to fact record shape for unified display
            for s in sessions:
                results.append({
                    "ref": f"[[{Path(s['path']).stem}]]",
                    "path": s["path"],
                    "tags": ["session"] + s.get("tags", []),
                    "date": s.get("date", ""),
                    "content": s.get("content", ""),
                })

    if not results:
        _print("[dim]No results found.[/dim]" if _RICH else "No results found.")
        return

    if _RICH:
        t = Table(
            title=f"Search results ({len(results)})",
            box=rich_box.ROUNDED,
            show_lines=False,
        )
        t.add_column("Ref", style="bold cyan", no_wrap=True)
        t.add_column("Tags", style="dim")
        t.add_column("Date", style="dim", no_wrap=True)
        t.add_column("Preview")

        for r in results:
            ref = r.get("ref", Path(r["path"]).stem)
            tag_str = ", ".join(r.get("tags", [])[:4])
            if len(r.get("tags", [])) > 4:
                tag_str += "…"
            date_str = r.get("date", "")
            preview = _preview(r.get("content", ""))
            score = r.get("score")
            if score is not None:
                preview = f"[dim]{score:.2f}[/dim] {preview}"
            t.add_row(ref, tag_str, date_str, preview)

        _console.print(t)
    else:
        for r in results:
            ref = r.get("ref", Path(r["path"]).stem)
            date_str = r.get("date", "")
            preview = _preview(r.get("content", ""), 60)
            print(f"{ref:<40} {date_str:<12} {preview}")


# ---------------------------------------------------------------------------
# Command: list
# ---------------------------------------------------------------------------

def cmd_list(args: argparse.Namespace) -> None:
    """List facts, sessions, tags, or projects."""
    list_type = args.list_type

    if list_type == "facts":
        _cmd_list_facts(args)
    elif list_type == "sessions":
        _cmd_list_sessions(args)
    elif list_type == "tags":
        _cmd_list_tags(args)
    elif list_type == "projects":
        _cmd_list_projects(args)
    else:
        _err(f"Unknown list type: {list_type!r}. Use: facts, sessions, tags, projects")


def _cmd_list_facts(args: argparse.Namespace) -> None:
    from lib.storage import search_facts

    scope_tags: list[str] = []
    if getattr(args, "project", None):
        scope_tags.append(f"project/{args.project}")
    if getattr(args, "tags", None):
        scope_tags.extend(t.strip() for t in args.tags.split(","))

    results = search_facts(
        tags=scope_tags or None,
        exclude_tags=["session"],
        limit=getattr(args, "limit", 50),
        sort_by=getattr(args, "sort", "date"),
    )

    if not results:
        _print("[dim]No facts found.[/dim]" if _RICH else "No facts found.")
        return

    if _RICH:
        t = Table(title=f"Facts ({len(results)})", box=rich_box.SIMPLE)
        t.add_column("Ref", style="bold cyan")
        t.add_column("Tags", style="dim")
        t.add_column("Date", style="dim", no_wrap=True)
        t.add_column("Preview")
        for r in results:
            ref = r.get("ref", Path(r["path"]).stem)
            tag_str = ", ".join(r.get("tags", [])[:3])
            t.add_row(ref, tag_str, r.get("date", ""), _preview(r.get("content", "")))
        _console.print(t)
    else:
        for r in results:
            ref = r.get("ref", Path(r["path"]).stem)
            print(f"{ref:<40} {r.get('date',''):<12} {_preview(r.get('content',''), 60)}")


def _cmd_list_sessions(args: argparse.Namespace) -> None:
    from lib.storage import search_sessions

    results = search_sessions(
        project=getattr(args, "project", None),
        text=getattr(args, "text", None),
        since=getattr(args, "since", None),
        until=getattr(args, "until", None),
        sort_by=getattr(args, "sort", "date"),
        limit=getattr(args, "limit", 20),
    )

    if not results:
        _print("[dim]No sessions found.[/dim]" if _RICH else "No sessions found.")
        return

    if _RICH:
        t = Table(title=f"Sessions ({len(results)})", box=rich_box.SIMPLE)
        t.add_column("Date", style="dim", no_wrap=True)
        t.add_column("Title", style="bold")
        t.add_column("Project", style="cyan")
        t.add_column("Summary")
        for s in results:
            t.add_row(
                s.get("date", ""),
                s.get("title", ""),
                s.get("project", ""),
                _truncate(s.get("summary", ""), 60),
            )
        _console.print(t)
    else:
        for s in results:
            print(f"{s.get('date',''):<12} {s.get('title',''):<30} {s.get('project',''):<15}")


def _cmd_list_tags(args: argparse.Namespace) -> None:
    from lib.storage import explore_tags

    tag_data = explore_tags()
    all_tags = tag_data.get("tags", [])

    sort_by = getattr(args, "sort", "count")
    if sort_by == "name":
        all_tags = sorted(all_tags, key=lambda t: t["name"])
    else:
        all_tags = sorted(all_tags, key=lambda t: t["count"], reverse=True)

    limit = getattr(args, "limit", 0)
    if limit:
        all_tags = all_tags[:limit]

    if not all_tags:
        _print("[dim]No tags found.[/dim]" if _RICH else "No tags found.")
        return

    if _RICH:
        t = Table(title=f"Tags ({len(all_tags)})", box=rich_box.SIMPLE)
        t.add_column("Tag", style="bold cyan")
        t.add_column("Files", justify="right")
        for tag in all_tags:
            t.add_row(tag["name"], str(tag["count"]))
        _console.print(t)
    else:
        for tag in all_tags:
            print(f"{tag['name']:<40} {tag['count']}")


def _cmd_list_projects(args: argparse.Namespace) -> None:
    from lib.storage import get_base_dir

    base = get_base_dir()

    projects: dict[str, dict] = {}

    # Discover projects from facts dir structure
    projects_dir = base / "projects"
    if projects_dir.exists():
        try:
            for d in sorted(projects_dir.iterdir()):
                if d.is_dir():
                    projects[d.name] = {"name": d.name, "facts": 0, "sessions": 0}
        except PermissionError:
            pass

    # Discover from sessions dir structure
    sp = base / "projects"
    if sp.exists():
        try:
            for d in sorted(sp.iterdir()):
                if d.is_dir() and d.name not in projects:
                    projects[d.name] = {"name": d.name, "facts": 0, "sessions": 0}
        except PermissionError:
            pass

    # Fall back to DB for project names when filesystem is inaccessible
    from lib.db import is_populated
    if is_populated():
        from lib.db import get_connection as _gc
        try:
            _rows = _gc().execute(
                "SELECT DISTINCT tag FROM file_tags WHERE tag LIKE 'project/%' ORDER BY tag"
            ).fetchall()
            for (_tag,) in _rows:
                _name = _tag[len("project/"):]
                if _name not in projects:
                    projects[_name] = {"name": _name, "facts": 0, "sessions": 0}
        except Exception:
            pass

    if not projects:
        _print("[dim]No projects found.[/dim]" if _RICH else "No projects found.")
        return

    # Enrich with counts from stats if DB is populated
    if is_populated():
        from lib.db import get_connection
        conn = get_connection()
        try:
            for name in projects:
                tag = f"project/{name}"
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
                projects[name]["facts"] = fr[0] if fr else 0
                projects[name]["sessions"] = sr[0] if sr else 0
        except Exception:
            pass

    proj_list = sorted(projects.values(), key=lambda p: p["facts"] + p["sessions"], reverse=True)

    if _RICH:
        t = Table(title=f"Projects ({len(proj_list)})", box=rich_box.SIMPLE)
        t.add_column("Project", style="bold cyan")
        t.add_column("Facts", justify="right")
        t.add_column("Sessions", justify="right")
        for p in proj_list:
            t.add_row(p["name"], str(p["facts"]), str(p["sessions"]))
        _console.print(t)
    else:
        for p in proj_list:
            print(f"{p['name']:<30} facts={p['facts']} sessions={p['sessions']}")


# ---------------------------------------------------------------------------
# Command: show
# ---------------------------------------------------------------------------

def cmd_show(args: argparse.Namespace) -> None:
    """Display the content of a memory file."""
    from lib.storage import find_file_by_stem
    from lib.tags import parse_front_matter

    stem = args.ref.strip("[]")
    path = find_file_by_stem(stem)
    if path is None:
        _err(f"No memory file found for ref: {stem!r}")
        sys.exit(1)

    content = path.read_text(encoding="utf-8")
    fm = parse_front_matter(content)

    if _RICH:
        # Front-matter as a compact info row
        meta_parts: list[str] = []
        if fm.get("date"):
            meta_parts.append(f"[dim]date:[/dim] {fm['date']}")
        if fm.get("tags"):
            meta_parts.append(f"[dim]tags:[/dim] {fm['tags']}")
        if fm.get("project"):
            meta_parts.append(f"[dim]project:[/dim] {fm['project']}")
        if meta_parts:
            _console.print("  ".join(meta_parts))

        # Strip front-matter for markdown rendering
        body = _strip_front_matter(content)
        _console.print(Panel(
            Markdown(body),
            title=f"[bold cyan]{path.name}[/bold cyan]",
            border_style="cyan",
            box=rich_box.ROUNDED,
        ))
    else:
        print(f"=== {path.name} ===")
        print(content)


def _strip_front_matter(content: str) -> str:
    """Remove the leading --- ... --- front-matter block."""
    lines = content.splitlines()
    if not lines or lines[0].strip() != "---":
        return content
    for i, line in enumerate(lines[1:], 1):
        if line.strip() == "---":
            return "\n".join(lines[i + 1:])
    return content


# ---------------------------------------------------------------------------
# Command: edit
# ---------------------------------------------------------------------------

def cmd_edit(args: argparse.Namespace) -> None:
    """Open a memory file in $EDITOR and re-index after editing."""
    from lib.storage import find_file_by_stem, get_base_dir
    from lib.db import index_file

    stem = args.ref.strip("[]")
    path = find_file_by_stem(stem)
    if path is None:
        _err(f"No memory file found for ref: {stem!r}")
        sys.exit(1)

    editor = os.environ.get("EDITOR", "nano")
    _print(f"Opening [bold cyan]{path.name}[/bold cyan] in {editor}…" if _RICH
           else f"Opening {path.name} in {editor}…")

    result = subprocess.run([editor, str(path)])
    if result.returncode != 0:
        _warn(f"Editor exited with code {result.returncode}")
        return

    # Re-index the file in SQLite cache
    base = get_base_dir()
    try:
        rel = str(path.relative_to(base))
        index_file(rel_path=rel, abs_path=path, base_dir=base)
        _ok(f"Re-indexed {rel}")
    except ValueError:
        _warn("File is outside base_dir — skipping SQLite re-index")

    # Re-embed content vector
    from lib.storage import first_paragraph
    from lib.vector_store import content_store
    from lib.tags import all_tags_for_file
    if content_store.enabled:
        try:
            content = path.read_text(encoding="utf-8")
            body = _strip_front_matter(content)
            file_tags = all_tags_for_file(path, base, content)
            rel = str(path.relative_to(base))
            content_store.upsert(
                id=rel,
                text=first_paragraph(body),
                payload={"path": rel, "tags": file_tags},
            )
            _ok("Re-embedded content vector")
        except Exception as exc:
            _warn(f"Re-embedding failed: {exc}")


# ---------------------------------------------------------------------------
# Command: delete
# ---------------------------------------------------------------------------

def cmd_delete(args: argparse.Namespace) -> None:
    """Remove a memory file from disk and from the index."""
    from lib.storage import find_file_by_stem, get_base_dir
    from lib.db import remove_file
    from lib.vector_store import content_store

    stem = args.ref.strip("[]")
    path = find_file_by_stem(stem)
    if path is None:
        _err(f"No memory file found for ref: {stem!r}")
        sys.exit(1)

    # Show preview before delete
    content = path.read_text(encoding="utf-8")
    if _RICH:
        _console.print(Panel(
            _truncate(content, 300),
            title=f"[bold red]About to delete:[/bold red] {path.name}",
            border_style="red",
        ))
    else:
        print(f"About to delete: {path}")
        print(_truncate(content, 200))

    # Confirm unless --yes
    if not getattr(args, "yes", False):
        try:
            answer = input("Delete this file? [y/N] ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            _print("\nAborted.")
            return
        if answer not in ("y", "yes"):
            _print("Aborted.")
            return

    # Remove from vector store first (needs rel path)
    base = get_base_dir()
    try:
        rel = str(path.relative_to(base))
    except ValueError:
        rel = path.name

    if content_store.enabled:
        content_store.delete(rel)

    # Remove from SQLite index
    remove_file(rel)

    # Remove from disk
    path.unlink()

    _ok(f"Deleted {path.name}")


# ---------------------------------------------------------------------------
# Command: reindex
# ---------------------------------------------------------------------------

def cmd_reindex(args: argparse.Namespace) -> None:
    """Rebuild file index and re-embed all content."""
    from lib.storage import get_base_dir, reindex as reindex_content
    from lib.db import reindex as reindex_files

    base = get_base_dir()

    if _RICH:
        _console.print(f"[bold]Base dir:[/bold] {base}")

        with Progress(
            SpinnerColumn(),
            TextColumn("{task.description}"),
            transient=False,
            console=_console,
        ) as p:
            t1 = p.add_task("Reindexing files…")
            file_stats = reindex_files(base, force=args.force)
            p.update(t1, description=(
                f"[green]Files indexed:[/green] {file_stats['indexed']} new, "
                f"{file_stats['unchanged']} unchanged, {file_stats['deleted']} deleted "
                f"(total {file_stats['total']})"
            ))
            p.stop_task(t1)

            t2 = p.add_task("Embedding content vectors…")
            vec_stats = reindex_content()
            desc = (
                f"[green]Vectors:[/green] {vec_stats['embedded']} embedded, "
                f"{vec_stats['skipped']} skipped, total {vec_stats['total']}"
            )
            if "error" in vec_stats:
                desc += f" — [yellow]{vec_stats['error']}[/yellow]"
            p.update(t2, description=desc)
            p.stop_task(t2)
    else:
        print(f"Base dir: {base}")
        print("Reindexing files…")
        file_stats = reindex_files(base, force=args.force)
        print(f"  {file_stats['total']} total, {file_stats['indexed']} indexed, "
              f"{file_stats['deleted']} deleted, {file_stats['unchanged']} unchanged")
        print("Embedding content vectors…")
        vec_stats = reindex_content()
        print(f"  {vec_stats['total']} total, {vec_stats['embedded']} embedded, "
              f"{vec_stats['skipped']} skipped")
        if "error" in vec_stats:
            print(f"  Warning: {vec_stats['error']}")


# ---------------------------------------------------------------------------
# Command: clear-cache
# ---------------------------------------------------------------------------

def cmd_clear_cache(args: argparse.Namespace) -> None:
    """Delete the SQLite index DB (cache + vectors)."""
    db = _db_path()
    if not db.exists():
        _print(f"No cache found at {db}")
        return

    size_mb = db.stat().st_size / (1024 * 1024)
    wal = db.parent / (db.name + "-wal")
    shm = db.parent / (db.name + "-shm")

    removed: list[str] = []
    for f in (db, wal, shm):
        if f.exists():
            f.unlink()
            removed.append(f.name)

    _ok(f"Removed: {', '.join(removed)} ({size_mb:.1f} MB)")
    _print("Cache will be rebuilt on next use.")


# ---------------------------------------------------------------------------
# Command: export-vectors
# ---------------------------------------------------------------------------

def cmd_export_vectors(args: argparse.Namespace) -> None:
    """Export all vectors from all collections to a JSON file."""
    import sqlite3

    db = _db_path()
    if not db.exists():
        _err(f"No cache DB at {db}")
        sys.exit(1)

    conn = sqlite3.connect(str(db), timeout=5.0)
    rows = conn.execute(
        "SELECT collection, id, vector, payload FROM vectors ORDER BY collection, id"
    ).fetchall()
    conn.close()

    if not rows:
        _print("No vectors found in the database.")
        return

    export: dict[str, list[dict]] = {}
    for collection, vid, blob, payload_json in rows:
        vec = list(struct.unpack(f"<{len(blob) // 4}f", blob))
        payload = json.loads(payload_json) if payload_json else {}
        export.setdefault(collection, []).append(
            {"id": vid, "vector": vec, "payload": payload}
        )

    output = Path(args.output)
    output.write_text(json.dumps(export, ensure_ascii=False, indent=2), encoding="utf-8")

    total = sum(len(v) for v in export.values())
    collections = ", ".join(f"{k} ({len(v)})" for k, v in export.items())
    _ok(f"Exported {total} vectors: {collections}")
    _print(f"Written to {output}")


# ---------------------------------------------------------------------------
# Command: import-vectors
# ---------------------------------------------------------------------------

def cmd_import_vectors(args: argparse.Namespace) -> None:
    """Import vectors from a JSON file into the SQLite store."""
    import sqlite3

    src = Path(args.file)
    if not src.exists():
        _err(f"File not found: {src}")
        sys.exit(1)

    data = json.loads(src.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        _err("Invalid format: expected a JSON object with collection keys.")
        sys.exit(1)

    db = _db_path()
    db.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(db), timeout=5.0)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS vectors ("
        "  collection TEXT, id TEXT, vector BLOB, payload TEXT,"
        "  PRIMARY KEY (collection, id))"
    )

    if args.replace:
        conn.execute("DELETE FROM vectors")
        _print("Cleared existing vectors.")

    total = 0
    for collection, points in data.items():
        if not isinstance(points, list):
            _warn(f"Skipping collection {collection!r}: expected a list.")
            continue
        for point in points:
            vid = point["id"]
            vec = point["vector"]
            payload = point.get("payload", {})
            blob = struct.pack(f"<{len(vec)}f", *vec)
            conn.execute(
                "INSERT OR REPLACE INTO vectors (collection, id, vector, payload) "
                "VALUES (?, ?, ?, ?)",
                (collection, vid, blob, json.dumps(payload, ensure_ascii=False)),
            )
            total += 1

    conn.commit()
    conn.close()

    collections = ", ".join(f"{k} ({len(v)})" for k, v in data.items() if isinstance(v, list))
    _ok(f"Imported {total} vectors: {collections}")


# ---------------------------------------------------------------------------
# Argument parser setup
# ---------------------------------------------------------------------------

def _build_parser() -> argparse.ArgumentParser:
    """Build and return the top-level argument parser."""
    parser = argparse.ArgumentParser(
        prog="ai-memory",
        description="CLI tool for ai-memory — debug, observe, and manage memory.",
    )
    sub = parser.add_subparsers(dest="command")

    # status
    sub.add_parser("status", help="Show cache DB location, size, and index stats")

    # health
    sub.add_parser("health", help="Run integrity checks on the DB and filesystem")

    # stats
    sub.add_parser("stats", help="Show aggregate memory statistics")

    # search
    p_search = sub.add_parser("search", help="Search facts and sessions")
    p_search.add_argument("query", nargs="?", help="Free-text query (semantic if AI_MEMORY_EMBEDDING enabled, else substring match for sessions)")
    p_search.add_argument("--tags", help="Comma-separated tags (AND filter)")
    p_search.add_argument("--any-tags", dest="any_tags", help="Comma-separated tags (OR filter)")
    p_search.add_argument("--project", help="Filter by project name")
    p_search.add_argument("--since", help="Start date YYYY-MM-DD")
    p_search.add_argument("--until", help="End date YYYY-MM-DD")
    p_search.add_argument("--type", choices=["facts", "sessions", "all"], default="all",
                          help="What to search (default: all)")
    p_search.add_argument("--limit", type=int, default=20, help="Max results (default: 20)")

    # list
    p_list = sub.add_parser("list", help="List facts, sessions, tags, or projects")
    p_list.add_argument("list_type", choices=["facts", "sessions", "tags", "projects"])
    p_list.add_argument("--project", help="Filter by project name")
    p_list.add_argument("--tags", help="Comma-separated tags (AND filter, for facts)")
    p_list.add_argument("--since", help="Start date YYYY-MM-DD (for sessions)")
    p_list.add_argument("--until", help="End date YYYY-MM-DD (for sessions)")
    p_list.add_argument("--text", help="Substring filter (for sessions)")
    p_list.add_argument("--sort", choices=["date", "modified", "count", "name"],
                        default="date", help="Sort order")
    p_list.add_argument("--limit", type=int, default=50, help="Max results (default: 50)")

    # show
    p_show = sub.add_parser("show", help="Display a memory file by ref")
    p_show.add_argument("ref", help="Wikilink stem or [[ref]]")

    # edit
    p_edit = sub.add_parser("edit", help="Open a memory file in $EDITOR")
    p_edit.add_argument("ref", help="Wikilink stem or [[ref]]")

    # delete
    p_del = sub.add_parser("delete", help="Remove a memory file from disk and index")
    p_del.add_argument("ref", help="Wikilink stem or [[ref]]")
    p_del.add_argument("--yes", action="store_true", help="Skip confirmation prompt")

    # reindex
    p_reindex = sub.add_parser("reindex", help="Rebuild file index and re-embed content")
    p_reindex.add_argument("--force", action="store_true",
                           help="Force reindex even if mtime unchanged")

    # clear-cache
    sub.add_parser("clear-cache", help="Delete the SQLite index/cache DB")

    # export-vectors
    p_export = sub.add_parser("export-vectors", help="Export vectors to a JSON file")
    p_export.add_argument("-o", "--output", default="ai-memory-vectors.json",
                          help="Output file path (default: ai-memory-vectors.json)")

    # import-vectors
    p_import = sub.add_parser("import-vectors", help="Import vectors from a JSON file")
    p_import.add_argument("file", help="JSON file to import")
    p_import.add_argument("--replace", action="store_true",
                          help="Clear all vectors before import (default: merge/upsert)")

    return parser


_COMMAND_MAP: dict[str, object] = {
    "status": cmd_status,
    "health": cmd_health,
    "stats": cmd_stats,
    "search": cmd_search,
    "list": cmd_list,
    "show": cmd_show,
    "edit": cmd_edit,
    "delete": cmd_delete,
    "reindex": cmd_reindex,
    "clear-cache": cmd_clear_cache,
    "export-vectors": cmd_export_vectors,
    "import-vectors": cmd_import_vectors,
}


def _dispatch(args: argparse.Namespace) -> None:
    """Call the appropriate command function for parsed args."""
    fn = _COMMAND_MAP.get(args.command)
    if fn is None:
        _err(f"Unknown command: {args.command!r}")
        sys.exit(1)
    fn(args)  # type: ignore[call-arg]


# ---------------------------------------------------------------------------
# Interactive shell
# ---------------------------------------------------------------------------

_SHELL_HELP = """\
Commands:
  status                       Show cache DB stats
  health                       Run integrity checks
  stats                        Aggregate statistics
  search [query] [--tags t1,t2] [--type facts|sessions|all]  Search
  list facts|sessions|tags|projects [--project ..]
  show <ref>                   Display a memory file
  edit <ref>                   Edit in $EDITOR and re-index
  delete <ref> [--yes]         Delete a memory file
  reindex [--force]            Rebuild index + embeddings
  clear-cache                  Delete the SQLite cache DB
  export-vectors [-o file]     Export vectors to JSON
  import-vectors <file>        Import vectors from JSON
  help                         Show this help
  exit / quit                  Exit the shell
"""


def cmd_shell(_args: argparse.Namespace | None = None) -> None:
    """Start the interactive shell (REPL)."""
    parser = _build_parser()

    all_commands = list(_COMMAND_MAP.keys()) + ["help", "exit", "quit"]
    completer = None

    if _PROMPT_TOOLKIT:
        history_file = Path.home() / "Library" / "Caches" / "ai-memory" / "cli_history"
        if sys.platform != "darwin":
            xdg = os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache"))
            history_file = Path(xdg) / "ai-memory" / "cli_history"
        history_file.parent.mkdir(parents=True, exist_ok=True)

        completer = WordCompleter(
            all_commands + ["--force", "--yes", "--tags", "--project",
                            "--since", "--until", "--type", "--limit",
                            "facts", "sessions", "tags", "projects"],
            ignore_case=True,
            sentence=False,
        )
        style = PtStyle.from_dict({"prompt": "bold ansicyan"})
        session: PromptSession = PromptSession(
            history=FileHistory(str(history_file)),
            completer=completer,
            style=style,
            complete_while_typing=True,
        )

    if _RICH:
        _console.print(Panel(
            "[bold cyan]ai-memory shell[/bold cyan]  Type [bold]help[/bold] for commands, [bold]exit[/bold] to quit.",
            border_style="cyan",
            box=rich_box.ROUNDED,
        ))
    else:
        print("ai-memory shell — type 'help' for commands, 'exit' to quit.")

    while True:
        try:
            if _PROMPT_TOOLKIT:
                line = session.prompt(HTML("<prompt>memory</prompt>> "))
            else:
                line = input("memory> ")
        except (EOFError, KeyboardInterrupt):
            _print("\nBye.")
            break

        line = line.strip()
        if not line:
            continue

        tokens = shlex.split(line)
        cmd = tokens[0].lower()

        if cmd in ("exit", "quit"):
            _print("Bye.")
            break

        if cmd == "help":
            _print(_SHELL_HELP)
            continue

        # Dispatch via main parser
        try:
            args = parser.parse_args(tokens)
            _dispatch(args)
        except SystemExit:
            pass  # argparse prints its own error; just continue the loop
        except Exception as exc:
            _err(str(exc))


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main() -> None:
    """Entry point for the ai-memory CLI."""
    parser = _build_parser()

    # No subcommand → interactive shell
    if len(sys.argv) == 1:
        cmd_shell()
        return

    args = parser.parse_args()
    if args.command is None:
        parser.print_help()
        sys.exit(1)

    _dispatch(args)


if __name__ == "__main__":
    main()
