# @ai-generated(solo)
from __future__ import annotations
"""ContentVectorStore — embeds text content for semantic search.

Unlike TagVectorStore (short text keys, dedup), this stores embeddings of
longer content (fact bodies, session summaries) and retrieves them by
semantic similarity to a query string.

Single collection "content" holds both facts and sessions.  Sessions are
distinguished by a "session" tag in their tags list.  IDs are relative
file paths for both.

Freshness: an MD5 hash of the embedded text is stored in the vector
payload under the "_md5" key.  On upsert, the existing payload is
fetched; if the hash matches, the embedding API call is skipped entirely.
"""

import hashlib
from dataclasses import dataclass
from typing import Any

from .. import embedding
from .base import SearchResult, VectorStore


@dataclass
class ContentHit:
    """A semantic search result with id, score, and metadata.

    Attributes:
        id:      stored point identifier (relative file path)
        score:   cosine similarity score
        payload: metadata dict stored alongside the vector
    """
    id: str
    score: float
    payload: dict[str, Any]


def _md5(text: str) -> str:
    """Return hex MD5 digest of a UTF-8 string."""
    return hashlib.md5(text.encode()).hexdigest()


class ContentVectorStore:
    """Embed text content on write; find semantically similar content on read.

    Lazy backend initialisation (same pattern as TagVectorStore) so the
    singleton can be created at module level before env vars are read.

    MD5 freshness: the ``_md5`` payload field stores a hash of the text
    that was last embedded.  Before calling the embedding API, the store
    fetches the existing payload and compares hashes — a match means the
    content hasn't changed and the upsert is skipped.

    When embedding is disabled (no OPENAI_API_KEY), upsert is a no-op
    and search returns an empty list.

    Args:
        collection: VectorStore collection name (e.g. "content")
    """

    def __init__(self, collection: str) -> None:
        self._collection = collection
        self._store: VectorStore | None = None

    @property
    def enabled(self) -> bool:
        """True when OPENAI_API_KEY is set and embedding calls will succeed."""
        return embedding.is_enabled()

    def upsert(self, id: str, text: str, payload: dict[str, Any]) -> None:
        """Embed text and store the vector with associated metadata.

        Skips re-embedding when the text MD5 matches the stored ``_md5``
        payload field.  No-op when embedding is disabled.

        Args:
            id:      unique identifier (relative file path)
            text:    content to embed (fact body, session summary+compact, etc.)
            payload: metadata to store alongside the vector; ``_md5`` is
                     added automatically and should not be supplied by callers
        """
        if not self.enabled:
            return
        digest = _md5(text)
        store = self._backend()
        existing = store.get_payloads([id])
        if (id in existing
                and existing[id].get("_md5") == digest
                and existing[id].get("_model", "text-embedding-3-small") == embedding.MODEL):
            return  # content and model unchanged — skip embedding
        vecs = embedding.embed_batch([text])
        vec = vecs[0] if vecs else None
        if vec is not None:
            store.upsert(id=id, vector=vec, payload={**payload, "_md5": digest, "_model": embedding.MODEL})

    def upsert_batch(self, items: list[tuple[str, str, dict[str, Any]]]) -> int:
        """Embed and store multiple items in a single embedding API call.

        Items whose MD5 and model both match the stored payload are skipped.

        Args:
            items: list of (id, text, payload) tuples

        Returns:
            Number of items actually embedded (excludes skipped items).
        """
        if not self.enabled or not items:
            return 0
        store = self._backend()
        ids = [item_id for item_id, _, _ in items]
        existing = store.get_payloads(ids)
        to_embed: list[tuple[str, str, dict[str, Any], str]] = []
        for item_id, text, payload in items:
            digest = _md5(text)
            if (item_id in existing
                    and existing[item_id].get("_md5") == digest
                    and existing[item_id].get("_model", "text-embedding-3-small") == embedding.MODEL):
                continue
            to_embed.append((item_id, text, payload, digest))
        if not to_embed:
            return 0
        texts = [text for _, text, _, _ in to_embed]
        vecs = embedding.embed_batch(texts)
        embedded = 0
        for (item_id, _, payload, digest), vec in zip(to_embed, vecs):
            if vec is not None:
                store.upsert(id=item_id, vector=vec, payload={**payload, "_md5": digest, "_model": embedding.MODEL})
                embedded += 1
        return embedded

    def search(
        self,
        query: str,
        top_k: int = 10,
        threshold: float = 0.3,
    ) -> list[ContentHit]:
        """Find stored content semantically similar to the query string.

        Embeds the query, then performs cosine similarity search against
        all stored vectors.

        Args:
            query:     natural-language search query
            top_k:     max results to return
            threshold: minimum cosine score (0.3 default — looser than tag
                       dedup because content similarity is more gradual)

        Returns:
            List of ContentHit sorted by score descending.
            Empty list when embedding is disabled or query embedding fails.
        """
        if not self.enabled:
            return []
        vecs = embedding.embed_batch([query])
        vec = vecs[0] if vecs else None
        if vec is None:
            return []
        store = self._backend()
        hits: list[SearchResult] = store.search(vec, top_k=top_k, threshold=threshold)
        return [
            ContentHit(
                id=h.id,
                score=h.score,
                # Strip internal _md5 from caller-visible payload
                payload={k: v for k, v in h.payload.items() if k not in ("_md5", "_model")},
            )
            for h in hits
        ]

    def delete(self, id: str) -> None:
        """Remove a stored vector by id. No-op if not found or disabled.

        Args:
            id: point identifier passed to upsert
        """
        if not self.enabled:
            return
        self._backend().delete(id)

    def _backend(self) -> VectorStore:
        """Lazily initialise and return the underlying VectorStore."""
        from .factory import get_vector_store
        from .. import embedding as emb
        if self._store is None:
            self._store = get_vector_store(self._collection)
            from .qdrant_store import QdrantVectorStore
            if isinstance(self._store, QdrantVectorStore):
                self._store.ensure_collection(emb.EMBEDDING_DIM)
        return self._store


# Module-level singleton — single "content" collection for facts + sessions
content_store = ContentVectorStore("content")
