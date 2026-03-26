# @ai-generated(solo)
from __future__ import annotations
"""OpenAI embedding backend.

Provides batch text embedding via OpenAI embedding models (default: text-embedding-3-small).
Model is selected via OPENAI_EMBEDDING_MODEL env var; EMBEDDING_DIM is derived automatically.
No external dependencies — uses stdlib urllib.

Public API:
  is_enabled() -> bool
  embed_batch(texts) -> list[list[float] | None]
"""

import json
import os
import urllib.request

_URL = "https://api.openai.com/v1/embeddings"

# Model can be overridden via env var; dimensions are derived automatically.
# Absent _model in stored payloads is treated as "text-embedding-3-small" for
# backwards compatibility — existing vectors are not invalidated on deploy.
MODEL: str = os.environ.get("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
_MODEL = MODEL  # kept for any external references
_DIM_MAP = {"text-embedding-3-large": 3072}
EMBEDDING_DIM: int = _DIM_MAP.get(MODEL, 1536)


def is_enabled() -> bool:
    """True when embedding is explicitly opted-in AND OPENAI_API_KEY is set.

    Requires AI_MEMORY_EMBEDDING=1 (or "true"/"yes") so that a globally
    configured OPENAI_API_KEY doesn't silently spend tokens.

    Returns:
        bool: True only when both the feature flag and the API key are present.
    """
    flag = os.environ.get("AI_MEMORY_EMBEDDING", "").lower()
    # why: default off — users with a global OPENAI_API_KEY shouldn't
    # silently spend tokens on embeddings they didn't ask for
    if flag not in ("1", "true", "yes"):
        return False
    return bool(os.environ.get("OPENAI_API_KEY"))


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
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        return [None] * len(texts)
    try:
        body = json.dumps({"input": texts, "model": _MODEL}).encode()
        req = urllib.request.Request(
            _URL,
            data=body,
            headers={
                "Authorization": f"Bearer {api_key}",
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
