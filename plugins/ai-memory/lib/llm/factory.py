# @ai-generated(solo)
from __future__ import annotations

"""LLM provider factory.

Reads ``lib.config.llm_cfg`` and returns the appropriate provider
instance.  Currently only ``openai`` is supported; add new backends
by extending the ``_BUILDERS`` dict.

Usage::

    from lib.llm.factory import get_provider
    llm = get_provider()            # uses config defaults
    result = llm.complete("...", MyType)
"""

from ..config import llm_cfg
from .base import LLMError, LLMProvider
from .openai import OpenAIProvider


def get_provider() -> LLMProvider:
    """Create an LLM provider from current config.

    Reads ``llm_cfg`` (model, api_key, provider) and returns the
    matching backend.  Raises if the feature is disabled or the
    provider is unknown.

    Returns:
        Configured LLMProvider instance.

    Raises:
        LLMError: if LLM feature is disabled, API key is missing,
                  or provider name is unrecognised.
    """
    cfg = llm_cfg
    if not cfg.enabled:
        raise LLMError(
            "LLM feature is disabled. "
            "Set AI_MEMORY_LLM=true and OPENAI_API_KEY to enable."
        )
    if not cfg.api_key:
        raise LLMError("OPENAI_API_KEY is not set.")

    provider = cfg.provider
    if provider == "openai":
        return OpenAIProvider(model=cfg.model, api_key=cfg.api_key)

    raise LLMError(f"Unknown LLM provider: {provider!r}")
