#!/usr/bin/env python3
# @ai-generated(solo)
"""Format-agnostic transcript loader.

Auto-detects transcript format (Claude Code vs Codex) and returns
normalized entries consumable by ``extract_message_stream`` and
``extract_llm_transcript``.

Detection strategy:
  1. Path heuristic: ``~/.codex/sessions/`` → Codex format.
  2. First-line probe: presence of ``"session_meta"`` type → Codex format.
  3. Default: Claude Code format (entries are already in the expected shape).
"""
from __future__ import annotations

import json
from pathlib import Path


def _is_codex_path(path: Path) -> bool:
    """Check if path looks like a Codex rollout file.

    Args:
        path: transcript file path.

    Returns:
        True if path is under ``~/.codex/sessions/`` or filename starts
        with ``rollout-``.
    """
    parts = path.parts
    # Check for .codex/sessions in path
    for i, part in enumerate(parts):
        if part == ".codex" and i + 1 < len(parts) and parts[i + 1] == "sessions":
            return True
    # Fallback: filename pattern
    return path.name.startswith("rollout-")


def _is_codex_content(path: Path) -> bool:
    """Probe first line of file for Codex ``session_meta`` marker.

    Args:
        path: transcript file path.

    Returns:
        True if first JSON line has ``type: "session_meta"``.
    """
    try:
        with path.open(encoding="utf-8", errors="replace") as f:
            first_line = f.readline().strip()
            if not first_line:
                return False
            entry = json.loads(first_line)
            return entry.get("type") == "session_meta"
    except Exception:
        return False


def detect_format(path: Path) -> str:
    """Detect transcript format: ``"codex"`` or ``"claude"``.

    Uses path heuristics first, then probes file content.

    Args:
        path: transcript file path.

    Returns:
        ``"codex"`` or ``"claude"``.
    """
    if _is_codex_path(path):
        return "codex"
    if _is_codex_content(path):
        return "codex"
    return "claude"


def find_transcript(session_id: str, agent: str = "claude") -> Path | None:
    """Search for the session transcript JSONL file.

    Looks in agent-specific locations:
      - claude: ``~/.claude/projects/**/{session_id}.jsonl``
      - codex:  ``~/.codex/sessions/**/*{session_id}*.jsonl``

    Args:
        session_id: session UUID.
        agent: agent identifier (``"claude"`` or ``"codex"``).

    Returns:
        Path to the .jsonl file, or None if not found.
    """
    search_dirs: list[tuple[Path, str]] = []
    if agent == "codex":
        search_dirs.append((Path.home() / ".codex" / "sessions", f"**/*{session_id}*.jsonl"))
    search_dirs.append((Path.home() / ".claude" / "projects", f"**/{session_id}.jsonl"))

    for base, pattern in search_dirs:
        if not base.exists():
            continue
        matches = list(base.glob(pattern))
        if matches:
            return matches[0]
    return None


def load_transcript(path: Path) -> list[dict]:
    """Load and normalize a transcript file, auto-detecting format.

    Returns entries in the Claude Code normalized format expected by
    ``extract_message_stream`` and ``extract_llm_transcript``.

    Args:
        path: path to the ``.jsonl`` transcript file.

    Returns:
        List of normalized entry dicts.
    """
    fmt = detect_format(path)

    if fmt == "codex":
        from .codex_session_loader import parse_codex_jsonl
        return parse_codex_jsonl(path)

    # Claude Code format — entries are already normalized
    entries: list[dict] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            entries.append(json.loads(line))
        except (json.JSONDecodeError, ValueError):
            pass
    return entries
