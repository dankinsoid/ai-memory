# @ai-generated(solo)
from __future__ import annotations
"""OpenAI chat completion helper for hook scripts.

Thin wrapper around gpt-4o-mini for classification/extraction tasks in hooks.
Uses stdlib urllib only — no external dependencies.

Public API:
  is_enabled() -> bool
  chat(system, user, **kwargs) -> str | None
  chat_json(system, user, **kwargs) -> dict | None

Graceful degradation: returns None when OPENAI_API_KEY is missing or API fails.
"""

import json
import os
import urllib.request

_URL = "https://api.openai.com/v1/chat/completions"

MODEL: str = os.environ.get("OPENAI_CHAT_MODEL", "gpt-4o-mini")


def is_enabled() -> bool:
    """True when OPENAI_API_KEY is set and chat-based features can work."""
    return bool(os.environ.get("OPENAI_API_KEY"))


def chat(
    system: str,
    user: str,
    *,
    model: str | None = None,
    temperature: float = 0.0,
    max_tokens: int = 256,
    timeout: int = 10,
) -> str | None:
    """Send a chat completion request, return assistant message text.

    Args:
        system: system prompt
        user: user message content
        model: override MODEL env/default
        temperature: sampling temperature (0.0 = deterministic)
        max_tokens: max response tokens
        timeout: HTTP timeout in seconds

    Returns:
        Assistant message content string, or None on any failure.
    """
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        return None
    try:
        body = json.dumps({
            "model": model or MODEL,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": temperature,
            "max_tokens": max_tokens,
        }).encode()
        req = urllib.request.Request(
            _URL,
            data=body,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read())
            return data["choices"][0]["message"]["content"]
    except Exception:
        return None


def chat_json(
    system: str,
    user: str,
    **kwargs,
) -> dict | None:
    """Send a chat completion and parse the response as JSON.

    Same args as chat(). Automatically requests json_object response format.

    Returns:
        Parsed dict from assistant response, or None on failure/invalid JSON.
    """
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        return None
    # Build request manually to add response_format
    model = kwargs.pop("model", None) or MODEL
    temperature = kwargs.pop("temperature", 0.0)
    max_tokens = kwargs.pop("max_tokens", 256)
    timeout = kwargs.pop("timeout", 10)
    try:
        body = json.dumps({
            "model": model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": temperature,
            "max_tokens": max_tokens,
            "response_format": {"type": "json_object"},
        }).encode()
        req = urllib.request.Request(
            _URL,
            data=body,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read())
            content = data["choices"][0]["message"]["content"]
            return json.loads(content)
    except Exception:
        return None
