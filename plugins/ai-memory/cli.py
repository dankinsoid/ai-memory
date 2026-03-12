#!/usr/bin/env python3
# @ai-generated(solo)
"""CLI tool for ai-memory plugin maintenance.

Commands:
  clear-cache     Delete the SQLite index/cache DB (vectors + file index).
                  Files remain intact — the DB is rebuilt lazily on next use.
  export-vectors  Export all vectors from all collections to a JSON file.
  import-vectors  Import vectors from a previously exported JSON file.
  reindex         Rebuild the file index and re-embed all content.
  status          Show cache location, size, and collection stats.

Usage:
  python3 cli.py clear-cache
  python3 cli.py export-vectors [-o vectors.json]
  python3 cli.py import-vectors vectors.json [--replace]
  python3 cli.py reindex [--force]
  python3 cli.py status
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import sys
from pathlib import Path

# Ensure lib/ is importable
sys.path.insert(0, str(Path(__file__).resolve().parent))


# ---------------------------------------------------------------------------
# DB path (duplicated from lib/db.py to avoid importing before clear-cache)
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
# Commands
# ---------------------------------------------------------------------------


def cmd_clear_cache(args: argparse.Namespace) -> None:
    """Delete the SQLite index DB (cache + vectors)."""
    db = _db_path()
    if not db.exists():
        print(f"No cache found at {db}")
        return

    size_mb = db.stat().st_size / (1024 * 1024)

    # Also remove WAL/SHM journal files
    wal = db.parent / (db.name + "-wal")
    shm = db.parent / (db.name + "-shm")

    removed: list[str] = []
    for f in (db, wal, shm):
        if f.exists():
            f.unlink()
            removed.append(f.name)

    print(f"Removed: {', '.join(removed)} ({size_mb:.1f} MB)")
    print("Cache will be rebuilt on next use.")


def cmd_export_vectors(args: argparse.Namespace) -> None:
    """Export all vectors from all collections to a JSON file."""
    db = _db_path()
    if not db.exists():
        print(f"No cache DB at {db}", file=sys.stderr)
        sys.exit(1)

    import sqlite3

    conn = sqlite3.connect(str(db), timeout=5.0)

    rows = conn.execute(
        "SELECT collection, id, vector, payload FROM vectors ORDER BY collection, id"
    ).fetchall()
    conn.close()

    if not rows:
        print("No vectors found in the database.")
        return

    # Group by collection
    export: dict[str, list[dict]] = {}
    for collection, vid, blob, payload_json in rows:
        vec = list(struct.unpack(f"<{len(blob) // 4}f", blob))
        payload = json.loads(payload_json) if payload_json else {}
        export.setdefault(collection, []).append({
            "id": vid,
            "vector": vec,
            "payload": payload,
        })

    output = Path(args.output)
    output.write_text(json.dumps(export, ensure_ascii=False, indent=2), encoding="utf-8")

    total = sum(len(v) for v in export.values())
    collections = ", ".join(f"{k} ({len(v)})" for k, v in export.items())
    print(f"Exported {total} vectors: {collections}")
    print(f"Written to {output}")


def cmd_import_vectors(args: argparse.Namespace) -> None:
    """Import vectors from a JSON file into the SQLite store."""
    src = Path(args.file)
    if not src.exists():
        print(f"File not found: {src}", file=sys.stderr)
        sys.exit(1)

    data = json.loads(src.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        print("Invalid format: expected a JSON object with collection keys.", file=sys.stderr)
        sys.exit(1)

    import sqlite3

    db = _db_path()
    db.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(db), timeout=5.0)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")

    # Ensure vectors table exists
    conn.execute(
        "CREATE TABLE IF NOT EXISTS vectors ("
        "  collection TEXT, id TEXT, vector BLOB, payload TEXT,"
        "  PRIMARY KEY (collection, id))"
    )

    if args.replace:
        # Clear all existing vectors before import
        conn.execute("DELETE FROM vectors")
        print("Cleared existing vectors.")

    total = 0
    for collection, points in data.items():
        if not isinstance(points, list):
            print(f"Skipping collection '{collection}': expected a list.", file=sys.stderr)
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
    print(f"Imported {total} vectors: {collections}")


def cmd_reindex(args: argparse.Namespace) -> None:
    """Rebuild file index and re-embed all content."""
    from lib.storage import get_base_dir, get_sessions_base_dir, reindex as reindex_content
    from lib.db import reindex as reindex_files

    base = get_base_dir()
    sessions_base = get_sessions_base_dir()

    print(f"Base dir: {base}")
    print(f"Reindexing files...")
    file_stats = reindex_files(base, sessions_base, force=args.force)
    print(f"  Files: {file_stats['total']} total, {file_stats['indexed']} indexed, "
          f"{file_stats['deleted']} deleted, {file_stats['unchanged']} unchanged")

    print("Embedding content vectors...")
    vec_stats = reindex_content()
    print(f"  Vectors: {vec_stats['total']} total, {vec_stats['embedded']} embedded, "
          f"{vec_stats['skipped']} skipped")
    if "error" in vec_stats:
        print(f"  Warning: {vec_stats['error']}")


def cmd_status(args: argparse.Namespace) -> None:
    """Show cache location, size, and collection stats."""
    db = _db_path()
    print(f"Cache DB: {db}")

    if not db.exists():
        print("  (not created yet)")
        return

    size_mb = db.stat().st_size / (1024 * 1024)
    print(f"  Size: {size_mb:.2f} MB")

    import sqlite3
    conn = sqlite3.connect(str(db), timeout=5.0)

    # File index stats
    try:
        row = conn.execute("SELECT COUNT(*) FROM files").fetchone()
        print(f"  Indexed files: {row[0]}")
    except sqlite3.OperationalError:
        print("  Indexed files: (table missing)")

    # Vector stats per collection
    try:
        rows = conn.execute(
            "SELECT collection, COUNT(*) FROM vectors GROUP BY collection ORDER BY collection"
        ).fetchall()
        if rows:
            print("  Vector collections:")
            for collection, count in rows:
                print(f"    {collection}: {count} vectors")
        else:
            print("  Vector collections: (empty)")
    except sqlite3.OperationalError:
        print("  Vector collections: (table missing)")

    # State entries
    try:
        row = conn.execute("SELECT COUNT(*) FROM state").fetchone()
        print(f"  State entries: {row[0]}")
    except sqlite3.OperationalError:
        pass

    conn.close()

    # Data dir
    from lib.storage import get_base_dir
    base = get_base_dir()
    md_count = sum(1 for _ in base.rglob("*.md"))
    print(f"\nData dir: {base}")
    print(f"  Files: {md_count} .md files")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    """Entry point for the ai-memory CLI."""
    parser = argparse.ArgumentParser(
        prog="ai-memory",
        description="CLI tool for ai-memory plugin maintenance.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # clear-cache
    sub.add_parser("clear-cache", help="Delete the SQLite index/cache DB")

    # export-vectors
    p_export = sub.add_parser("export-vectors", help="Export vectors to a JSON file")
    p_export.add_argument(
        "-o", "--output", default="ai-memory-vectors.json",
        help="Output file path (default: ai-memory-vectors.json)",
    )

    # import-vectors
    p_import = sub.add_parser("import-vectors", help="Import vectors from a JSON file")
    p_import.add_argument("file", help="JSON file to import")
    p_import.add_argument(
        "--replace", action="store_true",
        help="Clear all existing vectors before import (default: merge/upsert)",
    )

    # reindex
    p_reindex = sub.add_parser("reindex", help="Rebuild file index and re-embed content")
    p_reindex.add_argument(
        "--force", action="store_true",
        help="Force reindex even if mtime unchanged",
    )

    # status
    sub.add_parser("status", help="Show cache location, size, and collection stats")

    args = parser.parse_args()

    commands = {
        "clear-cache": cmd_clear_cache,
        "export-vectors": cmd_export_vectors,
        "import-vectors": cmd_import_vectors,
        "reindex": cmd_reindex,
        "status": cmd_status,
    }
    commands[args.command](args)


if __name__ == "__main__":
    main()
