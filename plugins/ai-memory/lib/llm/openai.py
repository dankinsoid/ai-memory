# @ai-generated(solo)
from __future__ import annotations

"""OpenAI structured-output provider.

Uses the ``response_format`` parameter with ``json_schema`` type
to get typed responses from OpenAI chat models. Stdlib only — no SDK.

Usage::

    from dataclasses import dataclass
    from lib.llm.openai import OpenAIProvider

    @dataclass
    class Tags:
        items: list[str]

    p = OpenAIProvider(model="gpt-4o-mini", api_key="sk-...")
    result = p.complete("Extract tags from: ...", Tags)
    print(result.items)
"""

import json
import types
import urllib.request
from dataclasses import fields
from typing import Any, Union, get_args, get_origin, get_type_hints

from .base import LLMError, LLMProvider, T

_URL = "https://api.openai.com/v1/chat/completions"

# Python type → JSON Schema type.
_SIMPLE: dict[type, str] = {
    str: "string",
    int: "integer",
    float: "number",
    bool: "boolean",
}


def _json_schema_for_type(tp: type) -> dict[str, Any]:
    """Convert a Python type annotation to a JSON Schema fragment.

    Supports: str, int, float, bool, list[T], Optional[T] (T | None).
    Nested dataclasses are not supported yet — add when needed.

    Args:
        tp: a type annotation (may include generics)

    Returns:
        JSON Schema dict describing the type.

    Raises:
        LLMError: if the type is not representable.
    """
    if tp in _SIMPLE:
        return {"type": _SIMPLE[tp]}

    origin = get_origin(tp)
    args = get_args(tp)

    # list[X]
    if origin is list and args:
        return {"type": "array", "items": _json_schema_for_type(args[0])}

    # Optional[X]  (Union[X, None] or X | None)
    if origin is Union or isinstance(tp, types.UnionType):
        non_none = [a for a in args if a is not type(None)]
        if len(non_none) == 1:
            schema = _json_schema_for_type(non_none[0])
            return {"anyOf": [schema, {"type": "null"}]}

    raise LLMError(f"Unsupported type for JSON schema: {tp}")


def _dataclass_to_schema(cls: type) -> dict[str, Any]:
    """Build an OpenAI-compatible JSON schema from a dataclass.

    All fields are required (OpenAI structured outputs constraint).
    Use ``Optional[X]`` for nullable fields, not default values.

    Args:
        cls: a dataclass class

    Returns:
        Complete JSON Schema object with ``type``, ``properties``,
        ``required``, and ``additionalProperties``.

    Raises:
        LLMError: if any field type is unsupported.
    """
    hints = get_type_hints(cls)
    props: dict[str, Any] = {}
    for f in fields(cls):
        props[f.name] = _json_schema_for_type(hints[f.name])
    return {
        "type": "object",
        "properties": props,
        "required": list(props.keys()),
        "additionalProperties": False,
    }


class OpenAIProvider(LLMProvider):
    """OpenAI chat-completion provider with structured output.

    Args:
        model:   model id (e.g. ``gpt-4o-mini``)
        api_key: OpenAI API key
        timeout: HTTP request timeout in seconds (default 30)

    Example::

        p = OpenAIProvider(model="gpt-4o-mini", api_key="sk-...")
        result = p.complete("hi", MyDataclass)
    """

    def __init__(self, model: str, api_key: str, *, timeout: int = 30) -> None:
        self._model = model
        self._api_key = api_key
        self._timeout = timeout

    def complete(self, prompt: str, response_type: type[T]) -> T:
        """Call OpenAI chat completion with structured output.

        Args:
            prompt:        text prompt (sent as a user message)
            response_type: dataclass class defining response schema

        Returns:
            Populated dataclass instance.

        Raises:
            LLMError: on network error, API error, or parse failure.
        """
        schema = _dataclass_to_schema(response_type)
        body = json.dumps({
            "model": self._model,
            "messages": [{"role": "user", "content": prompt}],
            "response_format": {
                "type": "json_schema",
                "json_schema": {
                    "name": response_type.__name__,
                    "strict": True,
                    "schema": schema,
                },
            },
        }).encode()

        req = urllib.request.Request(
            _URL,
            data=body,
            headers={
                "Authorization": f"Bearer {self._api_key}",
                "Content-Type": "application/json",
            },
        )

        try:
            with urllib.request.urlopen(req, timeout=self._timeout) as resp:
                data = json.loads(resp.read())
        except Exception as exc:
            raise LLMError(f"OpenAI API request failed: {exc}") from exc

        try:
            content = data["choices"][0]["message"]["content"]
            parsed = json.loads(content)
        except (KeyError, IndexError, json.JSONDecodeError) as exc:
            raise LLMError(f"Failed to parse OpenAI response: {exc}") from exc

        try:
            return response_type(**parsed)
        except TypeError as exc:
            raise LLMError(
                f"Response doesn't match {response_type.__name__}: {exc}"
            ) from exc
