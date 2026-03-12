# @ai-generated(guided)
from __future__ import annotations
"""SqliteVectorStore — SQLite backend with float32 BLOB vectors.

Replaces JsonVectorStore as the default local backend. Vectors are stored
as packed float32 arrays in BLOB columns, ~10x smaller than JSON encoding.
Search is brute-force cosine (same as JsonVectorStore) — suitable for < 1K items.

Uses the shared SQLite DB from ``lib.db`` (WAL mode, concurrent-read safe).
"""

import json
import struct
from typing import Any

from .base import SearchResult, VectorPoint, VectorStore, cosine


def _pack(vec: list[float]) -> bytes:
    """Serialize a float list to a packed float32 BLOB.

    Args:
        vec: list of floats (embedding vector)

    Returns:
        Packed bytes in little-endian float32 format.
    """
    return struct.pack(f"<{len(vec)}f", *vec)


def _unpack(blob: bytes) -> list[float]:
    """Deserialize a packed float32 BLOB to a float list.

    Args:
        blob: bytes from SQLite BLOB column

    Returns:
        List of floats (embedding vector).
    """
    return list(struct.unpack(f"<{len(blob) // 4}f", blob))


class SqliteVectorStore(VectorStore):
    """Vector store backed by the ``vectors`` table in the shared SQLite index.

    Each logical collection is a partition within the same table, keyed by
    ``(collection, id)``.  Brute-force cosine search loads all vectors for
    the collection — fast enough for the expected scale (< 1K points).

    Args:
        collection: logical collection name (e.g. "tags", "content")
    """

    def __init__(self, collection: str) -> None:
        self._collection = collection

    def _conn(self):
        """Lazy connection accessor — avoids import cycle at module level."""
        from ..db import get_connection
        return get_connection()

    def ensure_collection(self, dim: int) -> None:  # noqa: ARG002
        """No-op: schema is created by ``lib.db`` on first connection."""
        pass

    def upsert(self, id: str, vector: list[float], payload: dict[str, Any]) -> None:
        """Insert or replace a vector point.

        Args:
            id:      unique identifier within the collection
            vector:  embedding vector (float list)
            payload: arbitrary metadata dict (stored as JSON)
        """
        conn = self._conn()
        conn.execute(
            "INSERT OR REPLACE INTO vectors (collection, id, vector, payload) "
            "VALUES (?, ?, ?, ?)",
            (self._collection, id, _pack(vector), json.dumps(payload, ensure_ascii=False)),
        )
        conn.commit()

    def search(
        self,
        query_vector: list[float],
        top_k: int = 10,
        threshold: float = 0.0,
    ) -> list[SearchResult]:
        """Brute-force cosine search across all points in this collection.

        Args:
            query_vector: query embedding
            top_k:        max results
            threshold:    minimum cosine score to include

        Returns:
            List of SearchResult sorted by score descending.
        """
        conn = self._conn()
        rows = conn.execute(
            "SELECT id, vector, payload FROM vectors WHERE collection = ?",
            (self._collection,),
        ).fetchall()

        results = []
        for row_id, blob, payload_json in rows:
            vec = _unpack(blob)
            score = cosine(query_vector, vec)
            if score >= threshold:
                payload = json.loads(payload_json) if payload_json else {}
                results.append(SearchResult(id=row_id, score=score, payload=payload))

        results.sort(key=lambda r: r.score, reverse=True)
        return results[:top_k]

    def get_vectors(self, ids: list[str]) -> dict[str, list[float]]:
        """Return stored vectors for the given ids.

        Args:
            ids: point identifiers to look up

        Returns:
            Dict mapping found ids to their embedding vectors.
        """
        if not ids:
            return {}
        conn = self._conn()
        placeholders = ",".join("?" * len(ids))
        rows = conn.execute(
            f"SELECT id, vector FROM vectors "
            f"WHERE collection = ? AND id IN ({placeholders})",
            [self._collection, *ids],
        ).fetchall()
        return {row_id: _unpack(blob) for row_id, blob in rows}

    def get_payloads(self, ids: list[str]) -> dict[str, dict[str, Any]]:
        """Return stored payloads for the given ids.

        Args:
            ids: point identifiers to look up

        Returns:
            Dict mapping found ids to their payload dicts.
        """
        if not ids:
            return {}
        conn = self._conn()
        placeholders = ",".join("?" * len(ids))
        rows = conn.execute(
            f"SELECT id, payload FROM vectors "
            f"WHERE collection = ? AND id IN ({placeholders})",
            [self._collection, *ids],
        ).fetchall()
        return {
            row_id: json.loads(p) if p else {}
            for row_id, p in rows
        }

    def delete(self, id: str) -> None:
        """Remove a vector point by id. No-op if not found.

        Args:
            id: point identifier
        """
        conn = self._conn()
        conn.execute(
            "DELETE FROM vectors WHERE collection = ? AND id = ?",
            (self._collection, id),
        )
        conn.commit()

    def scroll_all(self) -> list[VectorPoint]:
        """Return all stored points in this collection.

        Returns:
            List of VectorPoint (id, vector, payload).
        """
        conn = self._conn()
        rows = conn.execute(
            "SELECT id, vector, payload FROM vectors WHERE collection = ?",
            (self._collection,),
        ).fetchall()
        return [
            VectorPoint(
                id=row_id,
                vector=_unpack(blob),
                payload=json.loads(p) if p else {},
            )
            for row_id, blob, p in rows
        ]


def migrate_from_json(collection: str, json_path: str) -> int:
    """One-time migration: import vectors from a JSON file into SQLite.

    Called by the factory when the JSON file exists and the SQLite collection
    is empty. Wrapped in a transaction for atomicity.

    Args:
        collection: logical collection name
        json_path:  path to the legacy JSON vector file

    Returns:
        Number of points migrated.
    """
    import os
    from pathlib import Path
    from ..db import get_connection

    path = Path(json_path)
    if not path.exists():
        return 0

    conn = get_connection()
    # Check if collection already has data — skip migration
    row = conn.execute(
        "SELECT 1 FROM vectors WHERE collection = ? LIMIT 1",
        (collection,),
    ).fetchone()
    if row is not None:
        return 0

    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return 0

    if not data:
        return 0

    count = 0
    for point_id, item in data.items():
        vec = item.get("vector", [])
        payload = item.get("payload", {})
        conn.execute(
            "INSERT OR REPLACE INTO vectors (collection, id, vector, payload) "
            "VALUES (?, ?, ?, ?)",
            (collection, point_id, _pack(vec), json.dumps(payload, ensure_ascii=False)),
        )
        count += 1

    conn.commit()

    # Rename JSON file to .bak so migration doesn't repeat
    try:
        os.rename(str(path), str(path) + ".bak")
    except OSError:
        pass  # non-critical — next startup will see collection is non-empty

    return count
