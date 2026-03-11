# @ai-generated(solo)
from __future__ import annotations
"""Local vector store backed by a JSON file.

Embeds text keys using OpenAI text-embedding-3-small and persists vectors
on disk. Supports cosine similarity search for tag deduplication.

Usage::

    store = VectorStore("tag-vectors.json")
    store.upsert(["testing", "architecture"])
    matches = store.find_similar(["tests"], {"testing", "architecture"})
    # → {"tests": "testing"}

Requires OPENAI_API_KEY. All methods are no-ops (or return empty results)
when the key is absent — callers check `.enabled` to decide on fallback.
"""

import json
import math
import os
import urllib.request
from pathlib import Path

_EMBEDDING_MODEL = "text-embedding-3-small"
_EMBED_URL = "https://api.openai.com/v1/embeddings"


class VectorStore:
    """Persistent vector store with cosine similarity search.

    Vectors are stored as a JSON dict {key: [float, ...]} in a single file
    under AI_MEMORY_DIR. The path is resolved lazily so the store can be
    instantiated at module level before any env vars are read.

    Args:
        filename: name of the JSON file inside AI_MEMORY_DIR
                  (e.g. "tag-vectors.json")
    """

    def __init__(self, filename: str) -> None:
        self._filename = filename

    # ------------------------------------------------------------------
    # Public interface
    # ------------------------------------------------------------------

    @property
    def enabled(self) -> bool:
        """True when OPENAI_API_KEY is set and embedding is available."""
        return bool(os.environ.get("OPENAI_API_KEY"))

    def upsert(self, keys: list[str]) -> None:
        """Embed and store any keys not yet in the store.

        Skips keys that already have a stored vector. No-op when disabled.

        Args:
            keys: strings to embed and persist (e.g. tag names)
        """
        if not self.enabled:
            return
        vectors = self._load()
        new_keys = [k for k in keys if k not in vectors]
        if not new_keys:
            return
        embeddings = self._embed_batch(new_keys)
        changed = False
        for key, vec in zip(new_keys, embeddings):
            if vec is not None:
                vectors[key] = vec
                changed = True
        if changed:
            self._save(vectors)

    def find_similar(
        self,
        queries: list[str],
        candidates: set[str],
        threshold: float = 0.88,
    ) -> dict[str, str | None]:
        """Return the best matching candidate for each query by cosine similarity.

        Exact matches (query in candidates) short-circuit without embedding.
        Candidates without a stored vector are skipped — they were never
        upserted, so the corpus is incomplete for them.

        Query embeddings are computed on-the-fly but not persisted (only
        upsert() writes to disk).

        Args:
            queries: approximate key names to resolve
            candidates: known keys to match against
            threshold: minimum cosine similarity; queries below this score
                       map to None (0.88 avoids false positives on short tags)

        Returns:
            Dict mapping each query to its best match, or None if none found.
            Exact matches map to themselves.
        """
        if not self.enabled:
            return {q: None for q in queries}

        vectors = self._load()

        # Embed query keys that aren't in candidates and lack a stored vector
        new_queries = [q for q in queries if q not in candidates and q not in vectors]
        if new_queries:
            embeddings = self._embed_batch(new_queries)
            for key, vec in zip(new_queries, embeddings):
                if vec is not None:
                    vectors[key] = vec
            # Not persisted — query-time embeddings are ephemeral

        result: dict[str, str | None] = {}
        for q in queries:
            if q in candidates:
                result[q] = q
                continue

            q_vec = vectors.get(q)
            if q_vec is None:
                result[q] = None
                continue

            best: str | None = None
            best_score = threshold
            for candidate in candidates:
                c_vec = vectors.get(candidate)
                if c_vec is None:
                    continue
                score = _cosine(q_vec, c_vec)
                if score > best_score:
                    best_score = score
                    best = candidate

            result[q] = best

        return result

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    @property
    def _path(self) -> Path:
        # Lazy resolution so the store can be created before env vars are set
        from .storage import get_base_dir
        return get_base_dir() / self._filename

    def _load(self) -> dict[str, list[float]]:
        """Load vectors from disk. Returns empty dict on missing file or error."""
        if not self._path.exists():
            return {}
        try:
            return json.loads(self._path.read_text(encoding="utf-8"))
        except Exception:
            return {}

    def _save(self, vectors: dict[str, list[float]]) -> None:
        """Persist vectors. Best-effort; errors are silently ignored."""
        try:
            self._path.write_text(json.dumps(vectors), encoding="utf-8")
        except Exception:
            pass

    def _embed_batch(self, texts: list[str]) -> list[list[float] | None]:
        """Embed multiple texts in a single OpenAI API call.

        Args:
            texts: non-empty list of strings to embed

        Returns:
            List of embedding vectors in the same order as input;
            individual items are None on API error.
        """
        api_key = os.environ.get("OPENAI_API_KEY")
        if not api_key:
            return [None] * len(texts)
        try:
            body = json.dumps({"input": texts, "model": _EMBEDDING_MODEL}).encode()
            req = urllib.request.Request(
                _EMBED_URL,
                data=body,
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json",
                },
            )
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = json.loads(resp.read())["data"]
                indexed = {item["index"]: item["embedding"] for item in data}
                return [indexed.get(i) for i in range(len(texts))]
        except Exception:
            return [None] * len(texts)


def _cosine(a: list[float], b: list[float]) -> float:
    """Cosine similarity between two equal-length vectors.

    Args:
        a, b: equal-length lists of floats

    Returns:
        Similarity in [-1.0, 1.0]; 0.0 if either vector has zero magnitude.
    """
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    if na == 0.0 or nb == 0.0:
        return 0.0
    return dot / (na * nb)


# Module-level instance used by storage.py and server.py
tag_store = VectorStore("tag-vectors.json")
