# @ai-generated(solo)
from __future__ import annotations
"""OpenAI embedding backend.

Provides batch text embedding via OpenAI text-embedding-3-small.
No external dependencies — uses stdlib urllib.

Public API:
  is_enabled() -> bool
  embed_batch(texts) -> list[list[float] | None]
"""

import json
import os
import urllib.request

_MODEL = "text-embedding-3-small"
_URL = "https://api.openai.com/v1/embeddings"
EMBEDDING_DIM = 1536


def is_enabled() -> bool:
    """True when OPENAI_API_KEY is set."""
    return bool(os.environ.get("OPENAI_API_KEY"))


def embed_batch(texts: list[str]) -> list[list[float] | None]:
    """Embed a list of texts in a single OpenAI API call.

    Args:
        texts: non-empty list of strings to embed

    Returns:
        List of 1536-dim float vectors in the same order as input.
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
