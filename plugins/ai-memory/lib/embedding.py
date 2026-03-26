# @ai-generated(solo)
from __future__ import annotations
"""OpenAI embedding backend.

Provides batch text embedding via OpenAI embedding models.
Model and feature flag are configured via ``lib.config.embedding_cfg``.
See ``lib.config`` for env vars (AI_MEMORY_EMBEDDING, AI_MEMORY_EMBEDDING_MODEL).
No external dependencies — uses stdlib urllib.

Public API:
  is_enabled() -> bool
  embed_batch(texts) -> list[list[float] | None]
"""

import json
import urllib.request

from .config import embedding_cfg

_URL = "https://api.openai.com/v1/embeddings"

# Re-export for callers that read these directly (vector_store, storage).
MODEL: str = embedding_cfg.model
EMBEDDING_DIM: int = embedding_cfg.dim


def is_enabled() -> bool:
    """True when embedding is explicitly opted-in AND OPENAI_API_KEY is set.

    Reads live from ``embedding_cfg`` (refreshed by ``config.reload()``).

    Returns:
        bool: True only when both the feature flag and the API key are present.
    """
    return embedding_cfg.enabled


def embed_batch(texts: list[str]) -> list[list[float] | None]:
    """Embed a list of texts in a single OpenAI API call.

    Args:
        texts: non-empty list of strings to embed

    Returns:
        List of EMBEDDING_DIM-dim float vectors in the same order as input.
        Individual items are None when the API call fails for any reason
        (missing key, network error, etc.) — callers should treat None
        as "embedding unavailable for this item" and skip gracefully.
    """
    cfg = embedding_cfg
    if not cfg.api_key:
        return [None] * len(texts)
    try:
        body = json.dumps({"input": texts, "model": cfg.model}).encode()
        req = urllib.request.Request(
            _URL,
            data=body,
            headers={
                "Authorization": f"Bearer {cfg.api_key}",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(req, timeout=15) as resp:
            items = json.loads(resp.read())["data"]
            # API returns items with an `index` field — reconstruct order
            by_index = {item["index"]: item["embedding"] for item in items}
            return [by_index.get(i) for i in range(len(texts))]
    except Exception:
        return [None] * len(texts)
