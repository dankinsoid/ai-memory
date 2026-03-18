#!/usr/bin/env python3
# @ai-generated(guided)
"""SQLite cache/index for ai-memory file storage.

The SQLite database is a **pure cache** over the filesystem — files remain
the source of truth.  The DB can be deleted at any time and rebuilt via
``reindex()``.

Location: ~/Library/Caches/ai-memory/index.db (macOS)
          ~/.cache/ai-memory/index.db (Linux)

Tables:
  files      — file metadata cache (rel_path, mtime, md5, tags_json, date)
  file_tags  — denormalised tag index for fast lookups
  vectors    — embedding vectors as float32 BLOBs (replaces json_store.py)
  state      — key-value store for hook state (replaces scattered JSON files)

Public API:
  get_connection() -> sqlite3.Connection
  is_populated() -> bool
  reindex(base_dir, force) -> dict
  index_file(rel_path, abs_path, base_dir) -> None
  remove_file(rel_path) -> None
  get_state(key) -> str | None
  set_state(key, value) -> None
  delete_state(key) -> None
  health_check(base_dir) -> dict
"""

from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import sys
from datetime import date, datetime, timezone
from pathlib import Path

from .tags import all_tags_for_file, parse_front_matter

# ---------------------------------------------------------------------------
# DB location
# ---------------------------------------------------------------------------

_SCHEMA_VERSION = 1


def _db_path() -> Path:
    """Return platform-appropriate cache path for the index DB.

    macOS: ~/Library/Caches/ai-memory/index.db
    Linux: $XDG_CACHE_HOME/ai-memory/index.db (default ~/.cache/ai-memory/)

    The cache directory is created if it doesn't exist.
    """
    if sys.platform == "darwin":
        cache = Path.home() / "Library" / "Caches" / "ai-memory"
    else:
        xdg = os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache"))
        cache = Path(xdg) / "ai-memory"
    cache.mkdir(parents=True, exist_ok=True)
    return cache / "index.db"


# ---------------------------------------------------------------------------
# Schema
# ---------------------------------------------------------------------------

_SCHEMA_SQL = """\
CREATE TABLE IF NOT EXISTS files (
    rel_path  TEXT PRIMARY KEY,
    mtime     REAL,
    md5       TEXT,
    tags_json TEXT,
    date      TEXT
);

CREATE TABLE IF NOT EXISTS file_tags (
    rel_path TEXT,
    tag      TEXT,
    PRIMARY KEY (rel_path, tag)
);
CREATE INDEX IF NOT EXISTS idx_file_tags_tag ON file_tags(tag);

CREATE TABLE IF NOT EXISTS vectors (
    collection TEXT,
    id         TEXT,
    vector     BLOB,
    payload    TEXT,
    PRIMARY KEY (collection, id)
);

CREATE TABLE IF NOT EXISTS state (
    key        TEXT PRIMARY KEY,
    value      TEXT,
    updated_at TEXT
);
"""


# ---------------------------------------------------------------------------
# Connection management
# ---------------------------------------------------------------------------

_conn: sqlite3.Connection | None = None


def get_connection() -> sqlite3.Connection:
    """Return a module-level SQLite connection, creating DB if needed.

    Uses WAL journal mode for concurrent read access from hook subprocesses.
    Applies schema migrations based on PRAGMA user_version.
    """
    global _conn
    if _conn is not None:
        # Guard against DB file being deleted while connection is open
        if _db_path().exists():
            return _conn
        # DB was deleted — close stale connection and recreate
        try:
            _conn.close()
        except Exception:
            pass
        _conn = None

    db = _db_path()
    conn = sqlite3.connect(str(db), timeout=5.0)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")

    version = conn.execute("PRAGMA user_version").fetchone()[0]
    if version < _SCHEMA_VERSION:
        conn.executescript(_SCHEMA_SQL)
        conn.execute(f"PRAGMA user_version={_SCHEMA_VERSION}")
        conn.commit()

    _conn = conn
    return conn


def close_connection() -> None:
    """Close the module-level connection if open."""
    global _conn
    if _conn is not None:
        try:
            _conn.close()
        except Exception:
            pass
        _conn = None


# ---------------------------------------------------------------------------
# Populated check
# ---------------------------------------------------------------------------


def is_populated() -> bool:
    """Return True if the files table has at least one row.

    Used by read-path functions to decide SQL vs filescan fallback.
    Returns False on any DB error (graceful degradation).
    """
    try:
        conn = get_connection()
        row = conn.execute("SELECT 1 FROM files LIMIT 1").fetchone()
        return row is not None
    except Exception:
        return False


# ---------------------------------------------------------------------------
# Single-file index
# ---------------------------------------------------------------------------


def _file_md5(path: Path) -> str:
    """Compute MD5 hex digest of a file's content."""
    return hashlib.md5(path.read_bytes()).hexdigest()


def index_file(rel_path: str, abs_path: Path, base_dir: Path) -> None:
    """Index (or re-index) a single file into the files + file_tags tables.

    Called on the write-path (after remember/upsert_session writes a .md file)
    to keep the index current without a full reindex.

    Args:
        rel_path: path relative to base_dir (e.g. "universal/my-rule.md")
        abs_path: absolute path to the file on disk
        base_dir: the AI_MEMORY_DIR root for tag derivation
    """
    try:
        content = abs_path.read_text(encoding="utf-8")
    except OSError:
        return

    fm = parse_front_matter(content)
    tags = all_tags_for_file(abs_path, base_dir, content)
    mtime = abs_path.stat().st_mtime
    md5 = hashlib.md5(content.encode("utf-8")).hexdigest()
    file_date = fm.get("date", "")

    conn = get_connection()
    conn.execute(
        "INSERT OR REPLACE INTO files (rel_path, mtime, md5, tags_json, date) "
        "VALUES (?, ?, ?, ?, ?)",
        (rel_path, mtime, md5, json.dumps(tags), file_date),
    )
    # Replace tags: delete old, insert new
    conn.execute("DELETE FROM file_tags WHERE rel_path = ?", (rel_path,))
    conn.executemany(
        "INSERT INTO file_tags (rel_path, tag) VALUES (?, ?)",
        [(rel_path, t) for t in tags],
    )
    conn.commit()


def remove_file(rel_path: str) -> None:
    """Remove a file's index entry (called when a file is deleted).

    Args:
        rel_path: path relative to base_dir
    """
    try:
        conn = get_connection()
        conn.execute("DELETE FROM file_tags WHERE rel_path = ?", (rel_path,))
        conn.execute("DELETE FROM files WHERE rel_path = ?", (rel_path,))
        conn.commit()
    except Exception:
        pass


# ---------------------------------------------------------------------------
# Full reindex
# ---------------------------------------------------------------------------


def reindex(
    base_dir: Path,
    force: bool = False,
) -> dict:
    """Reconcile filesystem ↔ SQLite index (mtime-based).

    Scans all .md files under base_dir (facts, rules, and sessions all live here).
    Uses mtime to skip unchanged files unless force=True.

    Args:
        base_dir: AI_MEMORY_DIR root
        force:    ignore mtime, re-parse everything

    Returns:
        Dict with keys: total, indexed, deleted, unchanged.
    """
    conn = get_connection()
    stats = {"total": 0, "indexed": 0, "deleted": 0, "unchanged": 0}

    # Collect all existing DB entries for orphan detection
    existing = {}
    for row in conn.execute("SELECT rel_path, mtime FROM files").fetchall():
        existing[row[0]] = row[1]

    seen: set[str] = set()

    # Commit every BATCH_SIZE writes to release the write lock periodically,
    # so concurrent MCP writes (index_file, set_state) don't get blocked
    # for the entire duration of a large reindex.
    _BATCH_SIZE = 50
    _pending = 0

    for md_file in base_dir.rglob("*.md"):
        try:
            rel = str(md_file.relative_to(base_dir))
        except ValueError:
            continue

        seen.add(rel)
        stats["total"] += 1

        try:
            st_mtime = md_file.stat().st_mtime
        except OSError:
            continue

        # Skip if mtime unchanged (unless force)
        if not force and rel in existing and existing[rel] == st_mtime:
            stats["unchanged"] += 1
            continue

        # (Re-)index this file
        try:
            content = md_file.read_text(encoding="utf-8")
        except OSError:
            continue

        fm = parse_front_matter(content)
        try:
            tags = all_tags_for_file(md_file, base_dir, content)
        except ValueError:
            tags = []
        md5 = hashlib.md5(content.encode("utf-8")).hexdigest()
        file_date = fm.get("date", "")

        conn.execute(
            "INSERT OR REPLACE INTO files (rel_path, mtime, md5, tags_json, date) "
            "VALUES (?, ?, ?, ?, ?)",
            (rel, st_mtime, md5, json.dumps(tags), file_date),
        )
        conn.execute("DELETE FROM file_tags WHERE rel_path = ?", (rel,))
        conn.executemany(
            "INSERT INTO file_tags (rel_path, tag) VALUES (?, ?)",
            [(rel, t) for t in tags],
        )
        stats["indexed"] += 1
        _pending += 1

        if _pending >= _BATCH_SIZE:
            conn.commit()
            _pending = 0

    # Remove orphans — DB entries with no file on disk
    for rel in existing:
        if rel not in seen:
            conn.execute("DELETE FROM file_tags WHERE rel_path = ?", (rel,))
            conn.execute("DELETE FROM files WHERE rel_path = ?", (rel,))
            stats["deleted"] += 1

    conn.commit()
    return stats


# ---------------------------------------------------------------------------
# Hook state key-value store
# ---------------------------------------------------------------------------


def get_state(key: str) -> str | None:
    """Read a hook state value by key.

    Args:
        key: state key (e.g. "{session_id}-loaded-rules")

    Returns:
        The stored JSON string, or None if not found.
    """
    try:
        conn = get_connection()
        row = conn.execute("SELECT value FROM state WHERE key = ?", (key,)).fetchone()
        return row[0] if row else None
    except Exception:
        return None


def set_state(key: str, value: str) -> None:
    """Write a hook state value (insert or replace).

    Args:
        key:   state key
        value: JSON string to store
    """
    try:
        conn = get_connection()
        conn.execute(
            "INSERT OR REPLACE INTO state (key, value, updated_at) VALUES (?, ?, ?)",
            (key, value, datetime.now(timezone.utc).isoformat()),
        )
        conn.commit()
    except Exception:
        pass


def delete_state(key: str) -> None:
    """Remove a hook state entry by key.

    Args:
        key: state key to delete
    """
    try:
        conn = get_connection()
        conn.execute("DELETE FROM state WHERE key = ?", (key,))
        conn.commit()
    except Exception:
        pass


# ---------------------------------------------------------------------------
# Health check
# ---------------------------------------------------------------------------


def health_check(base_dir: Path) -> dict:
    """Run integrity checks on the SQLite index and compare with filesystem.

    Checks DB integrity via PRAGMA, counts indexed vs on-disk files, detects
    orphan index entries (in DB but file deleted from disk), and reports
    vector collection sizes.

    Args:
        base_dir: AI_MEMORY_DIR root (facts, rules, and sessions all live here)

    Returns:
        Dict with keys:
          db_path (str), db_exists (bool), db_integrity (str | None),
          db_size_mb (float), indexed_count (int), fs_count (int),
          orphan_count (int), orphan_paths (list[str] — capped at 20),
          state_count (int), vector_collections (dict[str, int]).
    """
    db = _db_path()
    result: dict = {
        "db_path": str(db),
        "db_exists": db.exists(),
        "db_integrity": None,
        "db_size_mb": 0.0,
        "indexed_count": 0,
        "fs_count": 0,
        "orphan_count": 0,
        "orphan_paths": [],
        "state_count": 0,
        "vector_collections": {},
    }

    if not db.exists():
        return result

    result["db_size_mb"] = round(db.stat().st_size / (1024 * 1024), 3)

    try:
        conn = get_connection()

        # Integrity check (ok / error description)
        row = conn.execute("PRAGMA integrity_check").fetchone()
        result["db_integrity"] = row[0] if row else "unknown"

        # Indexed file count
        try:
            row = conn.execute("SELECT COUNT(*) FROM files").fetchone()
            result["indexed_count"] = row[0] if row else 0
        except sqlite3.OperationalError:
            pass

        # State entry count
        try:
            row = conn.execute("SELECT COUNT(*) FROM state").fetchone()
            result["state_count"] = row[0] if row else 0
        except sqlite3.OperationalError:
            pass

        # Vector collection sizes
        try:
            rows = conn.execute(
                "SELECT collection, COUNT(*) FROM vectors GROUP BY collection"
            ).fetchall()
            result["vector_collections"] = {r[0]: r[1] for r in rows}
        except sqlite3.OperationalError:
            pass

        # Orphan detection: entries in files table whose paths don't exist on disk
        try:
            rows = conn.execute("SELECT rel_path FROM files").fetchall()
            orphans: list[str] = []
            for (rel_path,) in rows:
                if not (base_dir / rel_path).exists():
                    orphans.append(rel_path)
            result["orphan_count"] = len(orphans)
            result["orphan_paths"] = orphans[:20]
        except sqlite3.OperationalError:
            pass

    except Exception:
        pass

    # Filesystem file count (done outside the DB try-block — independent check)
    try:
        result["fs_count"] = sum(1 for _ in base_dir.rglob("*.md"))
    except OSError:
        pass

    return result
