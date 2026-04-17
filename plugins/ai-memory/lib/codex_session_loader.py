#!/usr/bin/env python3
# @ai-generated(solo)
"""Codex CLI transcript parser.

Parses Codex rollout JSONL files (``~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl``)
into normalized entries compatible with the Claude Code format consumed by
``extract_message_stream`` and ``extract_llm_transcript``.

Codex JSONL envelope: ``{timestamp, type, payload}`` with types:
  - ``session_meta``   — first line: session id, cwd, git info
  - ``turn_context``   — per-turn: model, approval policy
  - ``response_item``  — canonical content: messages, reasoning, tool calls
  - ``event_msg``      — telemetry + duplicate text (ignored)

Key differences from Claude Code format:
  - Tool calls are **separate top-level items** (not nested content blocks),
    paired by ``call_id``.
  - ``arguments`` and ``output`` are JSON-encoded strings (double decode).
  - ``input_text`` / ``output_text`` content block types instead of ``text``.
  - No ``parentUuid`` — linear stream, no DAG.
"""
from __future__ import annotations

import json
from pathlib import Path


def _safe_json_loads(s: str) -> object:
    """Attempt JSON parse; return original string on failure.

    Codex encodes function_call arguments and outputs as JSON strings.
    Some may be plain text — return as-is in that case.
    """
    try:
        return json.loads(s)
    except (json.JSONDecodeError, TypeError):
        return s


def _parse_codex_entries(raw_entries: list[dict]) -> list[dict]:
    """Convert Codex JSONL entries into Claude-like normalized format.

    Output entries match the shape consumed by ``extract_message_stream``
    and ``extract_llm_transcript``::

        {
            "type": "user" | "assistant",
            "timestamp": str,
            "message": {
                "role": "user" | "assistant",
                "content": str | list[content_blocks]
            }
        }

    Tool calls are inlined as content blocks within assistant messages,
    and tool results as content blocks within user messages — mirroring
    how Claude Code structures its JSONL.

    Args:
        raw_entries: parsed JSONL dicts from a Codex rollout file.

    Returns:
        Normalized entries in Claude Code format.
    """
    # Phase 1: collect response_items, index tool call outputs by call_id
    items: list[dict] = []  # (timestamp, payload) for response_items
    tool_outputs: dict[str, dict] = {}  # call_id → output payload

    for entry in raw_entries:
        if entry.get("type") != "response_item":
            continue
        payload = entry.get("payload", {})
        ts = entry.get("timestamp", "")
        ptype = payload.get("type", "")

        if ptype == "function_call_output":
            call_id = payload.get("call_id", "")
            if call_id:
                tool_outputs[call_id] = {"payload": payload, "timestamp": ts}
        elif ptype == "custom_tool_call_output":
            call_id = payload.get("call_id", "")
            if call_id:
                tool_outputs[call_id] = {"payload": payload, "timestamp": ts}
        else:
            items.append({"timestamp": ts, "payload": payload})

    # Phase 2: build normalized entries
    normalized: list[dict] = []
    # Buffer for grouping consecutive assistant content blocks
    assistant_blocks: list[dict] = []
    assistant_ts: str = ""

    def _flush_assistant():
        """Emit buffered assistant content blocks as a single entry."""
        nonlocal assistant_blocks, assistant_ts
        if assistant_blocks:
            normalized.append({
                "type": "assistant",
                "timestamp": assistant_ts,
                "message": {
                    "role": "assistant",
                    "content": assistant_blocks,
                },
            })
            assistant_blocks = []
            assistant_ts = ""

    for item in items:
        ts = item["timestamp"]
        payload = item["payload"]
        ptype = payload.get("type", "")

        if ptype == "message":
            role = payload.get("role", "")
            raw_content = payload.get("content", [])

            # Normalize content blocks: input_text/output_text → text
            content_blocks: list[dict] = []
            for block in (raw_content if isinstance(raw_content, list) else []):
                if not isinstance(block, dict):
                    continue
                btype = block.get("type", "")
                if btype in ("input_text", "output_text"):
                    content_blocks.append({
                        "type": "text",
                        "text": block.get("text", ""),
                    })
                else:
                    content_blocks.append(block)

            if role == "user":
                _flush_assistant()
                # Attach any pending tool results to user entry
                # (Claude Code puts tool_result blocks in user messages)
                # — we'll handle this in a separate pass below
                normalized.append({
                    "type": "user",
                    "timestamp": ts,
                    "message": {
                        "role": "user",
                        "content": content_blocks,
                    },
                })
            elif role == "assistant":
                _flush_assistant()
                assistant_blocks = content_blocks
                assistant_ts = ts

        elif ptype == "function_call":
            # Built-in tool call: arguments is JSON-encoded string
            name = payload.get("name", "unknown")
            call_id = payload.get("call_id", "")
            raw_args = payload.get("arguments", "{}")
            parsed_args = _safe_json_loads(raw_args)

            tool_use_block = {
                "type": "tool_use",
                "id": call_id,
                "name": name,
                "input": parsed_args if isinstance(parsed_args, dict) else {"raw": parsed_args},
            }
            assistant_blocks.append(tool_use_block)
            if not assistant_ts:
                assistant_ts = ts

            # Inline the matching output as a tool_result in a user entry
            if call_id in tool_outputs:
                _flush_assistant()
                out_data = tool_outputs[call_id]
                raw_output = out_data["payload"].get("output", "")
                parsed_output = _safe_json_loads(raw_output)
                # Extract text from output
                if isinstance(parsed_output, dict):
                    result_text = parsed_output.get("output", str(parsed_output))
                else:
                    result_text = str(parsed_output)

                normalized.append({
                    "type": "user",
                    "timestamp": out_data["timestamp"],
                    "message": {
                        "role": "user",
                        "content": [{
                            "type": "tool_result",
                            "tool_use_id": call_id,
                            "content": result_text,
                        }],
                    },
                })

        elif ptype == "custom_tool_call":
            # Custom tool (e.g. apply_patch): input is raw string
            name = payload.get("name", "unknown")
            call_id = payload.get("call_id", "")
            raw_input = payload.get("input", "")

            tool_use_block = {
                "type": "tool_use",
                "id": call_id,
                "name": name,
                "input": {"raw": raw_input},
            }
            assistant_blocks.append(tool_use_block)
            if not assistant_ts:
                assistant_ts = ts

            # Inline the matching output
            if call_id in tool_outputs:
                _flush_assistant()
                out_data = tool_outputs[call_id]
                raw_output = out_data["payload"].get("output", "")
                parsed_output = _safe_json_loads(raw_output)
                if isinstance(parsed_output, dict):
                    result_text = parsed_output.get("output", str(parsed_output))
                else:
                    result_text = str(parsed_output)

                normalized.append({
                    "type": "user",
                    "timestamp": out_data["timestamp"],
                    "message": {
                        "role": "user",
                        "content": [{
                            "type": "tool_result",
                            "tool_use_id": call_id,
                            "content": result_text,
                        }],
                    },
                })

        elif ptype == "reasoning":
            # Skip reasoning blocks (encrypted, not useful for digest)
            pass

    _flush_assistant()
    return normalized


def parse_codex_jsonl(path: Path) -> list[dict]:
    """Parse a Codex rollout JSONL file into normalized Claude-like entries.

    Args:
        path: path to the Codex ``.jsonl`` rollout file.

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

    return _parse_codex_entries(raw)


def extract_codex_meta(path: Path) -> dict:
    """Extract session metadata from a Codex rollout JSONL file.

    Reads the ``session_meta`` entry and first ``turn_context`` entry.

    Args:
        path: path to the Codex ``.jsonl`` rollout file.

    Returns:
        Dict with optional keys: ``session_id``, ``cwd``, ``model``,
        ``cli_version``, ``git``.
    """
    meta: dict = {}
    found_turn = False

    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            entry = json.loads(line)
        except (json.JSONDecodeError, ValueError):
            continue

        etype = entry.get("type", "")
        payload = entry.get("payload", {})

        if etype == "session_meta":
            meta["session_id"] = payload.get("id", "")
            meta["cwd"] = payload.get("cwd", "")
            meta["cli_version"] = payload.get("cli_version", "")
            meta["git"] = payload.get("git")

        elif etype == "turn_context" and not found_turn:
            meta["model"] = payload.get("model", "")
            found_turn = True

        # Stop early once we have both
        if "session_id" in meta and found_turn:
            break

    return meta
