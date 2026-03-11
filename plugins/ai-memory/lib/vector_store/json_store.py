# @ai-generated(solo)
from __future__ import annotations
"""JsonVectorStore — JSON file backend with brute-force cosine search.

No external dependencies. Suitable for small collections (< ~1K items).
"""

import json
from pathlib import Path
from typing import Any

from .base import SearchResult, VectorPoint, VectorStore, cosine


class JsonVectorStore(VectorStore):
    """Vector store backed by a single JSON file.

    File format: {id: {"vector": [...], "payload": {...}}}

    All reads and writes go through _load/_save; no in-memory cache so
    concurrent processes see each other's writes (at the cost of extra I/O).
    Acceptable for the plugin's write frequency (one save per memory_remember).

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
            SearchResult(
                id=id,
                score=cosine(query_vector, item["vector"]),
                payload=item.get("payload", {}),
            )
            for id, item in self._load().items()
        ]
        results = [r for r in results if r.score >= threshold]
        results.sort(key=lambda r: r.score, reverse=True)
        return results[:top_k]

    def get_vectors(self, ids: list[str]) -> dict[str, list[float]]:
        data = self._load()
        return {id: data[id]["vector"] for id in ids if id in data}

    def get_payloads(self, ids: list[str]) -> dict[str, dict[str, Any]]:
        data = self._load()
        return {id: data[id].get("payload", {}) for id in ids if id in data}

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
