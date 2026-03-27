# @ai-generated(solo)
from __future__ import annotations

"""Claude CLI provider for structured LLM completions.

Calls ``claude -p`` in ``--bare`` mode (no hooks/plugins/memory) to avoid
recursion when ai-memory itself triggers LLM calls.  Structured output
is obtained via ``--json-schema`` + ``--output-format json``, reading the
``structured_output`` field from the CLI's JSON envelope.

Usage::

    from dataclasses import dataclass
    from lib.llm.claude_cli import ClaudeCLIProvider

    @dataclass
    class Tags:
        items: list[str]

    p = ClaudeCLIProvider(model="haiku")
    result = p.complete("Extract tags from: ...", Tags)
    print(result.items)
"""

import json
import subprocess
from dataclasses import fields
from typing import Any, Union, get_args, get_origin, get_type_hints
import types

from .base import LLMError, LLMProvider, T

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
            # JSON Schema nullable — use anyOf like OpenAI provider
            return {"anyOf": [schema, {"type": "null"}]}

    raise LLMError(f"Unsupported type for JSON schema: {tp}")


def _dataclass_to_schema(cls: type) -> dict[str, Any]:
    """Build a JSON schema from a dataclass for ``--json-schema``.

    Args:
        cls: a dataclass class

    Returns:
        JSON Schema object with type, properties, required.

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
    }


class ClaudeCLIProvider(LLMProvider):
    """Claude CLI provider with structured output via ``--json-schema``.

    Runs ``claude -p --bare`` as a subprocess so ai-memory hooks/plugins
    are not loaded (prevents infinite recursion).

    Args:
        model:   model alias or id (e.g. ``haiku``, ``claude-haiku-4-5-20251001``)
        timeout: subprocess timeout in seconds (default 60)

    Example::

        p = ClaudeCLIProvider(model="haiku")
        result = p.complete("Summarize: ...", MyDataclass)
    """

    def __init__(self, model: str, *, timeout: int = 60) -> None:
        self._model = model
        self._timeout = timeout

    def complete(self, prompt: str, response_type: type[T]) -> T:
        """Call Claude CLI with structured output.

        Args:
            prompt:        text prompt
            response_type: dataclass class defining response schema

        Returns:
            Populated dataclass instance.

        Raises:
            LLMError: on CLI error, timeout, or parse failure.
        """
        schema = _dataclass_to_schema(response_type)

        cmd = [
            "claude",
            "-p",
            prompt,
            "--bare",
            "--model", self._model,
            "--output-format", "json",
            "--json-schema", json.dumps(schema),
            "--no-session-persistence",
        ]

        try:
            proc = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=self._timeout,
            )
        except subprocess.TimeoutExpired as exc:
            raise LLMError(
                f"Claude CLI timed out after {self._timeout}s"
            ) from exc
        except FileNotFoundError as exc:
            raise LLMError(
                "claude CLI not found — is Claude Code installed?"
            ) from exc
        except Exception as exc:
            raise LLMError(f"Claude CLI failed: {exc}") from exc

        if proc.returncode != 0:
            raise LLMError(
                f"Claude CLI exited with code {proc.returncode}: "
                f"{proc.stderr.strip()}"
            )

        # Parse the JSON envelope from --output-format json
        try:
            envelope = json.loads(proc.stdout)
        except json.JSONDecodeError as exc:
            raise LLMError(
                f"Failed to parse Claude CLI JSON output: {exc}"
            ) from exc

        if envelope.get("is_error"):
            raise LLMError(
                f"Claude CLI returned error: {envelope.get('result', 'unknown')}"
            )

        # --json-schema puts validated output in structured_output
        parsed = envelope.get("structured_output")

        if parsed is None:
            # Fallback: try to extract JSON from the result text
            # Claude sometimes wraps JSON in markdown code blocks
            raw = envelope.get("result", "")
            parsed = _extract_json(raw)

        if parsed is None:
            raise LLMError(
                "Claude CLI response contains no structured_output and "
                "no parseable JSON in result text"
            )

        try:
            return response_type(**parsed)
        except TypeError as exc:
            raise LLMError(
                f"Response doesn't match {response_type.__name__}: {exc}"
            ) from exc


def _extract_json(text: str) -> dict[str, Any] | None:
    """Try to extract a JSON object from text, trimming non-JSON characters.

    Finds the first '{' and last '}' and attempts to parse the substring.

    Args:
        text: raw text that may contain a JSON object

    Returns:
        Parsed dict, or None if no valid JSON found.
    """
    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end <= start:
        return None
    try:
        return json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return None
