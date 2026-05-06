#!/usr/bin/env python3
# @ai-generated(solo)
"""Gemini CLI transcript parser.

Parses Gemini CLI chat JSONL files (~/.gemini/tmp/<project_hash>/chats/*.jsonl)
into normalized entries compatible with the Claude Code format consumed by
extract_message_stream and extract_llm_transcript.

Gemini JSONL format:
  - session_metadata  — first line: sessionId, projectHash, startTime, cwd
  - user              — user message: id, content[{text}]
  - gemini            — model response: id, content[{text}], model
  - message_update    — telemetry: id, tokens{input, output} (skipped here)

Key differences from Claude Code format:
  - Role "gemini" maps to "assistant".
  - Content blocks are bare {text} dicts; no "type" field.
  - No tool-call structure in the base transcript (tool results embedded in text).
"""
from __future__ import annotations

import json
from pathlib import Path


def _normalize_content(raw_content: list) -> list[dict]:
    """Convert Gemini content blocks to Claude-format blocks.

    Gemini uses ``[{"text": "..."}]`` without a ``type`` field.
    Claude Code expects ``[{"type": "text", "text": "..."}]``.

    Args:
        raw_content: list of Gemini content block dicts.

    Returns:
        List of normalized content blocks.
    """
    result = []
    for block in raw_content:
        if not isinstance(block, dict):
            continue
        if "text" in block and "type" not in block:
            result.append({"type": "text", "text": block["text"]})
        else:
            result.append(block)
    return result


def _parse_gemini_entries(raw_entries: list[dict]) -> list[dict]:
    """Convert Gemini JSONL entries into Claude-like normalized format.

    Output entries match the shape consumed by ``extract_message_stream``
    and ``extract_llm_transcript``::

        {
            "type": "user" | "assistant",
            "timestamp": str,
            "message": {
                "role": "user" | "assistant",
                "content": list[content_blocks]
            }
        }

    Args:
        raw_entries: parsed JSONL dicts from a Gemini chat file.

    Returns:
        Normalized entries in Claude Code format.
    """
    normalized: list[dict] = []

    for entry in raw_entries:
        etype = entry.get("type", "")

        # Skip metadata and telemetry entries — not conversation content
        if etype in ("session_metadata", "message_update"):
            continue

        raw_content = entry.get("content", [])
        if not isinstance(raw_content, list):
            raw_content = []
        content = _normalize_content(raw_content)

        if etype == "user":
            normalized.append({
                "type": "user",
                "timestamp": entry.get("timestamp", ""),
                "message": {
                    "role": "user",
                    "content": content,
                },
            })
        elif etype == "gemini":
            normalized.append({
                "type": "assistant",
                "timestamp": entry.get("timestamp", ""),
                "message": {
                    "role": "assistant",
                    "content": content,
                },
            })

    return normalized


def parse_gemini_jsonl(path: Path) -> list[dict]:
    """Parse a Gemini CLI chat JSONL file into normalized Claude-like entries.

    Args:
        path: path to the Gemini ``.jsonl`` chat file.

    Returns:
        List of normalized entries consumable by extract_message_stream
        and extract_llm_transcript.
    """
    raw: list[dict] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            raw.append(json.loads(line))
        except (json.JSONDecodeError, ValueError):
            pass
    return _parse_gemini_entries(raw)


def extract_gemini_meta(path: Path) -> dict:
    """Extract session metadata from a Gemini CLI chat JSONL file.

    Reads the ``session_metadata`` entry and the first ``gemini`` entry
    (for model name).

    Args:
        path: path to the Gemini ``.jsonl`` chat file.

    Returns:
        Dict with optional keys: ``session_id``, ``cwd``, ``model``.
    """
    meta: dict = {}

    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            entry = json.loads(line)
        except (json.JSONDecodeError, ValueError):
            continue

        etype = entry.get("type", "")

        if etype == "session_metadata":
            meta["session_id"] = entry.get("sessionId", "")
            meta["cwd"] = entry.get("cwd", "")

        elif etype == "gemini" and "model" not in meta:
            model = entry.get("model", "")
            if model:
                meta["model"] = model

        if "session_id" in meta and "model" in meta:
            break

    return meta
