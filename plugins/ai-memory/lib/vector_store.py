# @ai-generated(solo)
from __future__ import annotations
"""Vector store abstraction with JSON and Qdrant backends.

Mirrors the Clojure VectorStore protocol: low-level id/vector/payload
operations. High-level tag operations live in TagVectorStore on top.

Backends:
  JsonVectorStore    — JSON file + brute-force cosine; always available;
                       suitable for tags and rules (< ~1K items)
  QdrantVectorStore  — Qdrant REST API; needed for session/fact search
                       at scale

Backend selection (get_vector_store factory):
  QDRANT_URL set  → QdrantVectorStore
  otherwise       → JsonVectorStore

Public API:
  VectorStore      — ABC: upsert / search / delete / scroll_all
  SearchResult     — id, score, payload
  VectorPoint      — id, vector, payload
  get_vector_store(collection) -> VectorStore
  TagVectorStore   — high-level text-key operations on top of VectorStore
  tag_store        — module-level TagVectorStore("tags") singleton
"""

import json
import math
import os
import urllib.request
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from . import embedding


# ---------------------------------------------------------------------------
# Data types (mirror Clojure schema)
# ---------------------------------------------------------------------------


@dataclass
class SearchResult:
    """A single result from VectorStore.search.

    Attributes:
        id:      point identifier (same value passed to upsert)
        score:   cosine similarity in [−1.0, 1.0]
        payload: arbitrary dict stored alongside the vector
    """
    id: str
    score: float
    payload: dict[str, Any] = field(default_factory=dict)


@dataclass
class VectorPoint:
    """A stored vector point returned by VectorStore.scroll_all.

    Attributes:
        id:      point identifier
        vector:  embedding vector
        payload: arbitrary dict stored alongside the vector
    """
    id: str
    vector: list[float]
    payload: dict[str, Any] = field(default_factory=dict)


# ---------------------------------------------------------------------------
# Abstract base
# ---------------------------------------------------------------------------


class VectorStore(ABC):
    """Dense-vector similarity index.

    Mirrors the Clojure VectorStore protocol. All IDs are strings;
    implementations convert internally if the backend requires other types
    (e.g. Qdrant uses UUID strings).
    """

    @abstractmethod
    def ensure_collection(self, dim: int) -> None:
        """Initialize or verify the vector collection.

        Args:
            dim: embedding dimension (e.g. 1536 for text-embedding-3-small)
        """

    @abstractmethod
    def upsert(self, id: str, vector: list[float], payload: dict[str, Any]) -> None:
        """Insert or replace a vector point.

        Args:
            id:      unique point identifier (e.g. tag name or fact UUID)
            vector:  embedding vector, must match collection dim
            payload: arbitrary metadata stored with the point
        """

    @abstractmethod
    def search(
        self,
        query_vector: list[float],
        top_k: int = 10,
        threshold: float = 0.0,
    ) -> list[SearchResult]:
        """Find nearest neighbours by cosine similarity.

        Args:
            query_vector: query embedding, same dim as stored vectors
            top_k:        max results to return
            threshold:    minimum score to include (0.0 = no filter)

        Returns:
            List of SearchResult sorted by score descending.
        """

    @abstractmethod
    def delete(self, id: str) -> None:
        """Remove a vector point by id. No-op if not found.

        Args:
            id: point identifier passed to upsert
        """

    @abstractmethod
    def scroll_all(self) -> list[VectorPoint]:
        """Return all stored points.

        Returns:
            List of VectorPoint (id, vector, payload).
        """


# ---------------------------------------------------------------------------
# JSON backend (brute-force cosine, local file, no dependencies)
# ---------------------------------------------------------------------------


class JsonVectorStore(VectorStore):
    """Vector store backed by a single JSON file.

    Format: {id: {"vector": [...], "payload": {...}}}

    Suitable for small collections (< ~1K items). Cosine similarity is
    computed in pure Python — fast enough for tags and rules.

    Args:
        path: absolute path to the JSON file (created on first write)
    """

    def __init__(self, path: Path) -> None:
        self._path = path

    def ensure_collection(self, dim: int) -> None:  # noqa: ARG002
        # No-op: JSON file is schema-less; dim is not enforced.
        pass

    def upsert(self, id: str, vector: list[float], payload: dict[str, Any]) -> None:
        data = self._load()
        data[id] = {"vector": vector, "payload": payload}
        self._save(data)

    def search(
        self,
        query_vector: list[float],
        top_k: int = 10,
        threshold: float = 0.0,
    ) -> list[SearchResult]:
        results = [
            SearchResult(id=id, score=_cosine(query_vector, item["vector"]), payload=item.get("payload", {}))
            for id, item in self._load().items()
        ]
        results = [r for r in results if r.score >= threshold]
        results.sort(key=lambda r: r.score, reverse=True)
        return results[:top_k]

    def delete(self, id: str) -> None:
        data = self._load()
        if id in data:
            del data[id]
            self._save(data)

    def scroll_all(self) -> list[VectorPoint]:
        return [
            VectorPoint(id=id, vector=item["vector"], payload=item.get("payload", {}))
            for id, item in self._load().items()
        ]

    def _load(self) -> dict[str, Any]:
        if not self._path.exists():
            return {}
        try:
            return json.loads(self._path.read_text(encoding="utf-8"))
        except Exception:
            return {}

    def _save(self, data: dict[str, Any]) -> None:
        try:
            self._path.write_text(json.dumps(data), encoding="utf-8")
        except Exception:
            pass  # best-effort; don't fail callers


# ---------------------------------------------------------------------------
# Qdrant backend (REST API, no qdrant-client dependency)
# ---------------------------------------------------------------------------

_QDRANT_NS = uuid.UUID("6ba7b810-9dad-11d1-80b4-00c04fd430c8")  # UUID namespace


def _to_qdrant_id(id: str) -> str:
    """Convert an arbitrary string id to a UUID string for Qdrant.

    Qdrant requires IDs to be unsigned integers or UUIDs. We use UUID5
    (deterministic from the string) so the same id always maps to the
    same UUID and upsert is idempotent.

    Args:
        id: arbitrary string identifier

    Returns:
        UUID string (e.g. "6ba7b814-...")
    """
    return str(uuid.uuid5(_QDRANT_NS, id))


class QdrantVectorStore(VectorStore):
    """Vector store backed by the Qdrant REST API.

    Uses stdlib urllib — no qdrant-client dependency. Collection is
    created lazily on first ensure_collection call.

    Args:
        collection: Qdrant collection name
        url:        Qdrant server URL (e.g. "http://localhost:6333" or cloud URL)
        api_key:    Qdrant API key; None for unauthenticated local instances
    """

    def __init__(self, collection: str, url: str, api_key: str | None) -> None:
        self._collection = collection
        self._url = url.rstrip("/")
        self._api_key = api_key

    # ------------------------------------------------------------------
    # VectorStore interface
    # ------------------------------------------------------------------

    def ensure_collection(self, dim: int) -> None:
        """Create the collection if it does not exist.

        Uses Cosine distance to match the embedding model's similarity metric.

        Args:
            dim: vector dimension (must match the embeddings stored here)
        """
        try:
            self._request("GET", f"/collections/{self._collection}")
        except _QdrantError as e:
            if e.status == 404:
                self._request("PUT", f"/collections/{self._collection}", {
                    "vectors": {"size": dim, "distance": "Cosine"},
                })
            else:
                raise

    def upsert(self, id: str, vector: list[float], payload: dict[str, Any]) -> None:
        self._request("PUT", f"/collections/{self._collection}/points", {
            "points": [{"id": _to_qdrant_id(id), "vector": vector, "payload": {**payload, "_id": id}}],
        })

    def search(
        self,
        query_vector: list[float],
        top_k: int = 10,
        threshold: float = 0.0,
    ) -> list[SearchResult]:
        resp = self._request("POST", f"/collections/{self._collection}/points/search", {
            "vector": query_vector,
            "limit": top_k,
            "score_threshold": threshold,
            "with_payload": True,
        })
        return [
            SearchResult(
                id=hit["payload"].get("_id", hit["id"]),
                score=hit["score"],
                payload={k: v for k, v in hit["payload"].items() if k != "_id"},
            )
            for hit in resp.get("result", [])
        ]

    def delete(self, id: str) -> None:
        self._request("POST", f"/collections/{self._collection}/points/delete", {
            "points": [_to_qdrant_id(id)],
        })

    def scroll_all(self) -> list[VectorPoint]:
        points: list[VectorPoint] = []
        next_offset = None
        while True:
            body: dict[str, Any] = {
                "limit": 256,
                "with_payload": True,
                "with_vector": True,
            }
            if next_offset is not None:
                body["offset"] = next_offset
            resp = self._request("POST", f"/collections/{self._collection}/points/scroll", body)
            result = resp.get("result", {})
            for p in result.get("points", []):
                payload = p.get("payload", {})
                points.append(VectorPoint(
                    id=payload.get("_id", str(p["id"])),
                    vector=p["vector"],
                    payload={k: v for k, v in payload.items() if k != "_id"},
                ))
            next_offset = result.get("next_page_offset")
            if next_offset is None:
                break
        return points

    # ------------------------------------------------------------------
    # HTTP helpers
    # ------------------------------------------------------------------

    def _request(self, method: str, path: str, body: dict | None = None) -> dict:
        """Make a Qdrant REST API call and return the parsed JSON response.

        Args:
            method: HTTP method (GET, PUT, POST, DELETE)
            path:   URL path relative to the server root
            body:   optional request body (serialized as JSON)

        Returns:
            Parsed JSON response body as a dict.

        Raises:
            _QdrantError: on HTTP errors (status >= 400)
            Exception:    on network or parse errors
        """
        url = self._url + path
        data = json.dumps(body).encode() if body is not None else None
        headers: dict[str, str] = {"Content-Type": "application/json"}
        if self._api_key:
            headers["api-key"] = self._api_key
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                return json.loads(resp.read())
        except urllib.request.HTTPError as e:
            raise _QdrantError(e.code, e.read().decode(errors="replace")) from e


class _QdrantError(Exception):
    """HTTP error from the Qdrant API."""
    def __init__(self, status: int, body: str) -> None:
        super().__init__(f"Qdrant HTTP {status}: {body}")
        self.status = status


# ---------------------------------------------------------------------------
# Factory
# ---------------------------------------------------------------------------


def get_vector_store(collection: str) -> VectorStore:
    """Return the appropriate VectorStore backend for the environment.

    Selection order:
      1. QDRANT_URL set → QdrantVectorStore
      2. fallback       → JsonVectorStore in AI_MEMORY_DIR

    The returned store is not yet initialized; call ensure_collection(dim)
    before upserting if using Qdrant.

    Args:
        collection: logical collection name (e.g. "tags", "facts", "sessions")

    Returns:
        Configured VectorStore instance.
    """
    qdrant_url = os.environ.get("QDRANT_URL")
    if qdrant_url:
        return QdrantVectorStore(
            collection=collection,
            url=qdrant_url,
            api_key=os.environ.get("QDRANT_API_KEY"),
        )
    # Local JSON fallback — import here to avoid circular dependency
    from .storage import get_base_dir
    return JsonVectorStore(get_base_dir() / f"{collection}-vectors.json")


# ---------------------------------------------------------------------------
# High-level tag operations
# ---------------------------------------------------------------------------


class TagVectorStore:
    """High-level text-key vector store for tag deduplication.

    Wraps a VectorStore backend with embed-on-write and find-similar logic.
    The backend is created lazily from get_vector_store on first access.

    Enabled = embedding.is_enabled() (i.e. OPENAI_API_KEY is set).
    When disabled, upsert is a no-op and find_similar returns {q: None}.

    Args:
        collection: VectorStore collection name (e.g. "tags")
    """

    def __init__(self, collection: str) -> None:
        self._collection = collection
        self._store: VectorStore | None = None

    @property
    def enabled(self) -> bool:
        """True when embedding is available (OPENAI_API_KEY is set)."""
        return embedding.is_enabled()

    def upsert(self, keys: list[str]) -> None:
        """Embed any new keys and persist them to the vector store.

        Keys already in the store are skipped. No-op when disabled.

        Args:
            keys: text strings to embed and store (e.g. tag names)
        """
        if not self.enabled:
            return
        store = self._backend()
        existing_ids = {p.id for p in store.scroll_all()}
        new_keys = [k for k in keys if k not in existing_ids]
        if not new_keys:
            return
        vectors = embedding.embed_batch(new_keys)
        for key, vec in zip(new_keys, vectors):
            if vec is not None:
                store.upsert(id=key, vector=vec, payload={"key": key})

    def find_similar(
        self,
        queries: list[str],
        candidates: set[str],
        threshold: float = 0.88,
    ) -> dict[str, str | None]:
        """Find the best matching candidate for each query by cosine similarity.

        Exact matches short-circuit without any embedding call.
        For non-exact queries, embeds and searches against candidates that
        have a stored vector. Candidates without a vector are skipped.

        Args:
            queries:    text strings to resolve (e.g. approximate tag names)
            candidates: known existing keys to match against
            threshold:  minimum cosine score to accept; 0.88 avoids false
                        positives on short tag strings

        Returns:
            Dict mapping each query to its best match (or None if below
            threshold or no stored vector found). Exact matches map to
            themselves.
        """
        if not self.enabled:
            return {q: None for q in queries}

        store = self._backend()
        result: dict[str, str | None] = {}

        for q in queries:
            if q in candidates:
                result[q] = q
                continue

            q_vecs = embedding.embed_batch([q])
            q_vec = q_vecs[0]
            if q_vec is None:
                result[q] = None
                continue

            hits = store.search(q_vec, top_k=5, threshold=threshold)
            # Only accept hits whose key is in the known candidates set
            match = next(
                (h.payload.get("key") for h in hits if h.payload.get("key") in candidates),
                None,
            )
            result[q] = match

        return result

    def _backend(self) -> VectorStore:
        """Return the lazily initialized backend store."""
        if self._store is None:
            self._store = get_vector_store(self._collection)
            if isinstance(self._store, QdrantVectorStore):
                self._store.ensure_collection(embedding.EMBEDDING_DIM)
        return self._store


# ---------------------------------------------------------------------------
# Module-level singletons
# ---------------------------------------------------------------------------

tag_store = TagVectorStore("tags")


# ---------------------------------------------------------------------------
# Math helpers
# ---------------------------------------------------------------------------


def _cosine(a: list[float], b: list[float]) -> float:
    """Cosine similarity between two equal-length vectors.

    Args:
        a, b: equal-length lists of floats

    Returns:
        Similarity in [−1.0, 1.0]; 0.0 if either vector has zero magnitude.
    """
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    if na == 0.0 or nb == 0.0:
        return 0.0
    return dot / (na * nb)
