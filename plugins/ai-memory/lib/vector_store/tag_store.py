# @ai-generated(solo)
from __future__ import annotations
"""TagVectorStore — high-level text-key vector store for tag deduplication.

Wraps a VectorStore backend with embed-on-write and find-similar logic.
ID convention for tags: id = tag_name, payload = {"key": tag_name}.
"""

import os
from typing import TYPE_CHECKING

from .. import embedding
from .base import VectorStore

if TYPE_CHECKING:
    pass


class TagVectorStore:
    """Embed tag names on write and find similar tags by cosine similarity.

    The backend VectorStore is created lazily by get_vector_store on first
    access so the store can be instantiated at module level before env vars
    are read.

    Enabled = embedding.is_enabled() (OPENAI_API_KEY is set).
    When disabled, upsert is a no-op and find_similar returns {q: None}.

    ID convention:
        id      = tag_name  (e.g. "testing")
        payload = {"key": tag_name}

    Args:
        collection: VectorStore collection name (e.g. "tags")
    """

    def __init__(self, collection: str) -> None:
        self._collection = collection
        self._store: VectorStore | None = None

    @property
    def enabled(self) -> bool:
        """True when OPENAI_API_KEY is set and embedding calls will succeed."""
        return embedding.is_enabled()

    def upsert(self, keys: list[str]) -> None:
        """Embed any new tag names and persist them.

        Tags already in the store are skipped. No-op when disabled.

        Args:
            keys: tag name strings to embed and store
        """
        if not self.enabled:
            return
        store = self._backend()
        existing = store.get_payloads(keys)
        new_keys = [
            k for k in keys
            if k not in existing
            or existing[k].get("_model", "text-embedding-3-small") != embedding.MODEL
        ]
        if not new_keys:
            return
        vectors = embedding.embed_batch(new_keys)
        for key, vec in zip(new_keys, vectors):
            if vec is not None:
                store.upsert(id=key, vector=vec, payload={"key": key, "_model": embedding.MODEL})

    def find_similar(
        self,
        queries: list[str],
        candidates: set[str],
        threshold: float = 0.88,
    ) -> dict[str, str | None]:
        """Find the best matching candidate for each query by cosine similarity.

        Three-pass resolution to minimise embedding API calls:
          1. Exact string match in candidates → no embedding needed.
          2. Query already has a stored vector → retrieve it, no API call.
          3. Remaining queries → embed in a single batch call.

        Only candidates present in the `candidates` set are accepted as
        results; other stored tags outside that set are ignored.

        Args:
            queries:    tag name strings to resolve
            candidates: known existing tag names to match against
            threshold:  minimum cosine score; 0.88 avoids false positives
                        on short tag strings like "rule" vs "preference"

        Returns:
            Dict mapping each query to its best match (or None if no stored
            candidate exceeds the threshold). Exact matches map to themselves.
        """
        if not self.enabled:
            return {q: None for q in queries}

        store = self._backend()
        result: dict[str, str | None] = {}

        # Pass 1: exact string matches — free
        to_search = [q for q in queries if q not in candidates]
        for q in queries:
            if q in candidates:
                result[q] = q

        if not to_search:
            return result

        # Pass 2: fetch stored vectors in one batch — absent keys need embedding
        query_vectors: dict[str, list[float]] = store.get_vectors(to_search)
        need_embed = [q for q in to_search if q not in query_vectors]

        # Pass 3: batch-embed the truly new queries
        if need_embed:
            vecs = embedding.embed_batch(need_embed)
            for q, vec in zip(need_embed, vecs):
                if vec is not None:
                    query_vectors[q] = vec

        # Search for all non-exact queries
        for q in to_search:
            vec = query_vectors.get(q)
            if vec is None:
                result[q] = None
                continue
            hits = store.search(vec, top_k=5, threshold=threshold)
            result[q] = next(
                (h.payload.get("key") for h in hits if h.payload.get("key") in candidates),
                None,
            )

        return result

    def _backend(self) -> VectorStore:
        from .factory import get_vector_store
        from .. import embedding as emb
        if self._store is None:
            self._store = get_vector_store(self._collection)
            from .qdrant_store import QdrantVectorStore
            if isinstance(self._store, QdrantVectorStore):
                self._store.ensure_collection(emb.EMBEDDING_DIM)
        return self._store


# Module-level singleton — used by storage.py and server.py
tag_store = TagVectorStore("tags")
