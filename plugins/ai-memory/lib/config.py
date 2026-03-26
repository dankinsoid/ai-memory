# @ai-generated(solo)
from __future__ import annotations
"""Centralised env-var configuration for ai-memory.

All AI_MEMORY_* env vars are read here so feature modules don't
scatter os.environ lookups.  Import what you need::

    from lib.config import embedding_cfg, llm_cfg

Env vars
--------
Feature flags (default **off** — opt-in to avoid silent token spend):

  AI_MEMORY_EMBEDDING   "true"/"1"/"yes" to enable vector embeddings
  AI_MEMORY_LLM         "true"/"1"/"yes" to enable LLM calls (future)

Model selection:

  AI_MEMORY_EMBEDDING_MODEL   default "text-embedding-3-small"
  AI_MEMORY_LLM_MODEL         default "gpt-4o-mini"

Provider selection:

  AI_MEMORY_LLM_PROVIDER      default "openai"

API keys (read from env, typically set in settings.json):

  OPENAI_API_KEY        required for both embedding and LLM features
"""

import os
from dataclasses import dataclass


def _flag(name: str) -> bool:
    """Read a boolean env-var flag (default False).

    Args:
        name: environment variable name

    Returns:
        True when the value is "1", "true", or "yes" (case-insensitive).
    """
    return os.environ.get(name, "").lower() in ("1", "true", "yes")


def _api_key() -> str | None:
    """Return OPENAI_API_KEY or None.

    Returns:
        The key string if set and non-empty, else None.
    """
    return os.environ.get("OPENAI_API_KEY") or None


@dataclass(frozen=True)
class EmbeddingConfig:
    """Configuration for the embedding feature.

    Attributes:
        enabled: True when both the feature flag and API key are present.
        model:   OpenAI embedding model name.
        dim:     Embedding vector dimensionality (derived from model).
        api_key: OPENAI_API_KEY value or None.
    """

    enabled: bool
    model: str
    dim: int
    api_key: str | None


@dataclass(frozen=True)
class LLMConfig:
    """Configuration for LLM chat-completion features.

    Attributes:
        enabled:  True when both the feature flag and API key are present.
        model:    Chat model name (e.g. ``gpt-4o-mini``).
        api_key:  OPENAI_API_KEY value or None.
        provider: Backend name (``openai`` by default).
    """

    enabled: bool
    model: str
    api_key: str | None
    provider: str


_DIM_MAP = {"text-embedding-3-large": 3072}


def _load_embedding() -> EmbeddingConfig:
    flag = _flag("AI_MEMORY_EMBEDDING")
    key = _api_key()
    model = os.environ.get("AI_MEMORY_EMBEDDING_MODEL", "text-embedding-3-small")
    dim = _DIM_MAP.get(model, 1536)
    return EmbeddingConfig(
        enabled=flag and key is not None,
        model=model,
        dim=dim,
        api_key=key,
    )


def _load_llm() -> LLMConfig:
    flag = _flag("AI_MEMORY_LLM")
    key = _api_key()
    model = os.environ.get("AI_MEMORY_LLM_MODEL", "gpt-4o-mini")
    provider = os.environ.get("AI_MEMORY_LLM_PROVIDER", "openai")
    return LLMConfig(
        enabled=flag and key is not None,
        model=model,
        api_key=key,
        provider=provider,
    )


def reload() -> None:
    """Re-read env vars and update module-level configs.

    Useful after env changes in tests.  Not thread-safe (acceptable —
    MCP server is single-threaded).
    """
    global embedding_cfg, llm_cfg  # noqa: PLW0603
    embedding_cfg = _load_embedding()
    llm_cfg = _load_llm()


embedding_cfg: EmbeddingConfig = _load_embedding()
llm_cfg: LLMConfig = _load_llm()
