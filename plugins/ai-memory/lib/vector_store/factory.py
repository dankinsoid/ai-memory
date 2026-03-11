# @ai-generated(solo)
from __future__ import annotations
"""VectorStore factory — selects the backend based on environment variables.

Selection order:
  1. QDRANT_URL set  → QdrantVectorStore
  2. fallback        → JsonVectorStore  (AI_MEMORY_DIR/<collection>-vectors.json)
"""

import os

from .base import VectorStore
from .json_store import JsonVectorStore
from .qdrant_store import QdrantVectorStore


def get_vector_store(collection: str) -> VectorStore:
    """Return the appropriate VectorStore backend for the current environment.

    The returned store is uninitialised; call ensure_collection(dim) before
    the first upsert when using Qdrant (JsonVectorStore ignores it).

    Args:
        collection: logical collection name, e.g. "tags", "facts", "sessions"

    Returns:
        Configured VectorStore instance (JsonVectorStore or QdrantVectorStore).
    """
    qdrant_url = os.environ.get("QDRANT_URL")
    if qdrant_url:
        return QdrantVectorStore(
            collection=collection,
            url=qdrant_url,
            api_key=os.environ.get("QDRANT_API_KEY"),
        )
    # Lazy import to avoid circular dependency: storage ↔ vector_store
    from ..storage import get_base_dir
    return JsonVectorStore(get_base_dir() / f"{collection}-vectors.json")
