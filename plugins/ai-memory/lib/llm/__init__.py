# @ai-generated(solo)
"""LLM provider abstraction — structured completion.

Stdlib-only interface for calling LLMs with a typed response format.
Provider is selected via ``AI_MEMORY_LLM_PROVIDER`` env var (default ``openai``).

Usage::

    from dataclasses import dataclass
    from lib.llm import get_provider

    @dataclass
    class Sentiment:
        label: str
        score: float

    llm = get_provider()
    result = llm.complete("Classify: 'I love it'", Sentiment)
    # result.label == "positive", result.score == 0.95
"""

from .factory import get_provider

__all__ = ["get_provider"]
