# @ai-generated(solo)
from __future__ import annotations
"""QdrantVectorStore — Qdrant REST API backend.

No qdrant-client dependency; uses stdlib urllib only.
IDs are mapped to UUID5 (Qdrant requires UUID or uint) and the original
string ID is stored in payload under "_id" for round-trip recovery.
"""

import json
import urllib.request
import uuid
from typing import Any

from .base import SearchResult, VectorPoint, VectorStore

# Deterministic UUID namespace for string-to-UUID mapping
_NS = uuid.UUID("6ba7b810-9dad-11d1-80b4-00c04fd430c8")


def _to_uuid(id: str) -> str:
    """Map an arbitrary string id to a deterministic UUID5 string.

    Qdrant requires IDs to be unsigned integers or UUIDs. UUID5 gives a
    stable, collision-resistant mapping so upsert is idempotent.

    Args:
        id: arbitrary string identifier (e.g. tag name, file path)

    Returns:
        UUID string representation (e.g. "550e8400-e29b-41d4-a716-...")
    """
    return str(uuid.uuid5(_NS, id))


class QdrantVectorStore(VectorStore):
    """Vector store backed by the Qdrant REST API.

    Uses Cosine distance (matches OpenAI embedding model geometry).
    Collection is created on ensure_collection; upsert/search are idempotent.

    Args:
        collection: Qdrant collection name (one per logical data type)
        url:        Qdrant server URL, e.g. "http://localhost:6333"
        api_key:    Qdrant API key; None for unauthenticated local instances
    """

    def __init__(self, collection: str, url: str, api_key: str | None) -> None:
        self._collection = collection
        self._url = url.rstrip("/")
        self._api_key = api_key

    def ensure_collection(self, dim: int) -> None:
        """Create the collection if it does not exist.

        Idempotent: a 404 triggers creation; any other HTTP error propagates.

        Args:
            dim: vector dimension (must match stored embeddings)
        """
        try:
            self._req("GET", f"/collections/{self._collection}")
        except _QdrantError as e:
            if e.status == 404:
                self._req("PUT", f"/collections/{self._collection}", {
                    "vectors": {"size": dim, "distance": "Cosine"},
                })
            else:
                raise

    def upsert(self, id: str, vector: list[float], payload: dict[str, Any]) -> None:
        # Store original string id in payload for recovery after search/scroll
        self._req("PUT", f"/collections/{self._collection}/points", {
            "points": [{
                "id": _to_uuid(id),
                "vector": vector,
                "payload": {**payload, "_id": id},
            }],
        })

    def search(
        self,
        query_vector: list[float],
        top_k: int = 10,
        threshold: float = 0.0,
    ) -> list[SearchResult]:
        resp = self._req("POST", f"/collections/{self._collection}/points/search", {
            "vector": query_vector,
            "limit": top_k,
            "score_threshold": threshold,
            "with_payload": True,
        })
        return [
            SearchResult(
                id=hit["payload"].get("_id", str(hit["id"])),
                score=hit["score"],
                payload={k: v for k, v in hit["payload"].items() if k != "_id"},
            )
            for hit in resp.get("result", [])
        ]

    def get_vectors(self, ids: list[str]) -> dict[str, list[float]]:
        """Fetch stored vectors for a batch of ids in one Qdrant request.

        Uses POST /points with with_vector=True to retrieve only the points
        that exist, each with its vector. Missing ids are absent from the result.

        Args:
            ids: original string identifiers to look up

        Returns:
            Dict mapping found ids to their embedding vectors.
        """
        uuid_to_id = {_to_uuid(id): id for id in ids}
        resp = self._req("POST", f"/collections/{self._collection}/points", {
            "ids": list(uuid_to_id.keys()),
            "with_payload": False,
            "with_vector": True,
        })
        result = {}
        for p in resp.get("result", []):
            orig_id = uuid_to_id.get(str(p["id"]))
            if orig_id and p.get("vector") is not None:
                result[orig_id] = p["vector"]
        return result

    def get_payloads(self, ids: list[str]) -> dict[str, dict[str, Any]]:
        """Fetch stored payloads for a batch of ids in one Qdrant request.

        Args:
            ids: original string identifiers to look up

        Returns:
            Dict mapping found ids to their payload dicts.
        """
        uuid_to_id = {_to_uuid(id): id for id in ids}
        resp = self._req("POST", f"/collections/{self._collection}/points", {
            "ids": list(uuid_to_id.keys()),
            "with_payload": True,
            "with_vector": False,
        })
        result = {}
        for p in resp.get("result", []):
            orig_id = uuid_to_id.get(str(p["id"]))
            if orig_id:
                payload = p.get("payload", {})
                result[orig_id] = {k: v for k, v in payload.items() if k != "_id"}
        return result

    def delete(self, id: str) -> None:
        self._req("POST", f"/collections/{self._collection}/points/delete", {
            "points": [_to_uuid(id)],
        })

    def scroll_all(self) -> list[VectorPoint]:
        """Paginate through all stored points using Qdrant scroll API.

        Returns:
            All stored VectorPoints with original string IDs recovered from payload.
        """
        points: list[VectorPoint] = []
        next_offset = None
        while True:
            body: dict[str, Any] = {"limit": 256, "with_payload": True, "with_vector": True}
            if next_offset is not None:
                body["offset"] = next_offset
            resp = self._req("POST", f"/collections/{self._collection}/points/scroll", body)
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

    def _req(self, method: str, path: str, body: dict | None = None) -> dict:
        """Make a Qdrant REST API call.

        Args:
            method: HTTP verb (GET, PUT, POST, DELETE)
            path:   URL path relative to server root
            body:   optional JSON-serializable request body

        Returns:
            Parsed JSON response.

        Raises:
            _QdrantError: on HTTP 4xx/5xx responses
        """
        data = json.dumps(body).encode() if body is not None else None
        headers: dict[str, str] = {"Content-Type": "application/json"}
        if self._api_key:
            headers["api-key"] = self._api_key
        req = urllib.request.Request(self._url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                return json.loads(resp.read())
        except urllib.request.HTTPError as e:
            raise _QdrantError(e.code, e.read().decode(errors="replace")) from e


class _QdrantError(Exception):
    """HTTP error returned by the Qdrant API."""
    def __init__(self, status: int, body: str) -> None:
        super().__init__(f"Qdrant HTTP {status}: {body}")
        self.status = status
