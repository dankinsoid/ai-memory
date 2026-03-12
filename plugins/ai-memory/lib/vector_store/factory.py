# @ai-generated(guided)
from __future__ import annotations
"""VectorStore factory — selects the backend based on environment variables.

Selection order:
  1. QDRANT_URL set  → QdrantVectorStore
  2. fallback        → SqliteVectorStore  (shared index.db)

On first use, migrates any legacy JSON vector files into SQLite automatically.
"""

import os

from .base import VectorStore
from .qdrant_store import QdrantVectorStore
from .sqlite_store import SqliteVectorStore


def get_vector_store(collection: str) -> VectorStore:
    """Return the appropriate VectorStore backend for the current environment.

    The returned store is uninitialised; call ensure_collection(dim) before
    the first upsert when using Qdrant (SqliteVectorStore ignores it).

    On first call with SQLite backend, attempts one-time migration from
    any legacy ``{collection}-vectors.json`` file.

    Args:
        collection: logical collection name, e.g. "tags", "content"

    Returns:
        Configured VectorStore instance (SqliteVectorStore or QdrantVectorStore).
    """
    qdrant_url = os.environ.get("QDRANT_URL")
    if qdrant_url:
        return QdrantVectorStore(
            collection=collection,
            url=qdrant_url,
            api_key=os.environ.get("QDRANT_API_KEY"),
        )

    store = SqliteVectorStore(collection)

    # One-time migration from legacy JSON file
    from ..storage import get_base_dir
    json_path = get_base_dir() / f"{collection}-vectors.json"
    if json_path.exists():
        from .sqlite_store import migrate_from_json
        migrate_from_json(collection, str(json_path))

    return store
