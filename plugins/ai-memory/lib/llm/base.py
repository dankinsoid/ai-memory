# @ai-generated(solo)
from __future__ import annotations

"""Abstract LLM provider interface.

Defines the contract every LLM backend must implement: a single
``complete`` method that takes a prompt and a dataclass type, and
returns a populated instance of that type.
"""

from abc import ABC, abstractmethod
from dataclasses import fields, dataclass
from typing import TypeVar, get_type_hints

T = TypeVar("T")


class LLMProvider(ABC):
    """LLM backend that returns structured (typed) responses.

    Implementations must convert the dataclass type into whatever
    schema format the backend requires (e.g. OpenAI JSON schema,
    Anthropic tool_use), call the API, and deserialize the response
    back into a dataclass instance.

    Example::

        @dataclass
        class Summary:
            text: str
            bullet_count: int

        provider = OpenAIProvider(model="gpt-4o-mini", api_key="sk-...")
        result = provider.complete("Summarize: ...", Summary)
        print(result.text)
    """

    @abstractmethod
    def complete(self, prompt: str, response_type: type[T]) -> T:
        """Call the LLM and return a structured response.

        Args:
            prompt:        user/system prompt text
            response_type: a dataclass class whose fields define the
                           expected response schema. Supported field
                           types: str, int, float, bool, list[str],
                           list[int], list[float], Optional variants.

        Returns:
            An instance of *response_type* populated from the LLM output.

        Raises:
            LLMError: on API/network failures or unparseable responses.
        """


class LLMError(Exception):
    """Raised when an LLM call fails.

    Attributes:
        message: human-readable error description
    """
