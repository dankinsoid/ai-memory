#!/usr/bin/env python3
# @ai-generated(solo)
"""Format-agnostic transcript loader.

Auto-detects transcript format (Claude Code, Codex, or Gemini) and returns
normalized entries consumable by ``extract_message_stream`` and
``extract_llm_transcript``.

Detection strategy:
  1. Path heuristic: ``~/.codex/sessions/`` → Codex; ``/.gemini/tmp/`` → Gemini.
  2. First-line probe: ``"session_meta"`` type → Codex; ``"session_metadata"`` → Gemini.
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


def _is_gemini_path(path: Path) -> bool:
    """Check if path is under the Gemini CLI chat directory.

    Args:
        path: transcript file path.

    Returns:
        True if path contains ``/.gemini/tmp/``.
    """
    return "/.gemini/tmp/" in str(path)


def _is_gemini_content(path: Path) -> bool:
    """Probe first line of file for Gemini ``session_metadata`` marker.

    Gemini uses ``session_metadata`` (with underscore, unlike Codex's ``session_meta``).

    Args:
        path: transcript file path.

    Returns:
        True if first JSON line has ``type: "session_metadata"``.
    """
    try:
        with path.open(encoding="utf-8", errors="replace") as f:
            first_line = f.readline().strip()
            if not first_line:
                return False
            entry = json.loads(first_line)
            return entry.get("type") == "session_metadata"
    except Exception:
        return False


def _gemini_session_matches(path: Path, session_id: str) -> bool:
    """Check if a Gemini chat file belongs to the given session_id.

    Gemini filenames are timestamp-based, so session_id must be matched
    against the sessionId field in the session_metadata entry.

    Args:
        path: path to a candidate Gemini chat file.
        session_id: session UUID to match.

    Returns:
        True if the file's session_metadata.sessionId equals session_id.
    """
    try:
        with path.open(encoding="utf-8", errors="replace") as f:
            first_line = f.readline().strip()
        if not first_line:
            return False
        entry = json.loads(first_line)
        return (
            entry.get("type") == "session_metadata"
            and entry.get("sessionId") == session_id
        )
    except Exception:
        return False


def detect_format(path: Path) -> str:
    """Detect transcript format: ``"codex"``, ``"gemini"``, or ``"claude"``.

    Uses path heuristics first, then probes file content.

    Args:
        path: transcript file path.

    Returns:
        ``"codex"``, ``"gemini"``, or ``"claude"``.
    """
    if _is_codex_path(path):
        return "codex"
    if _is_gemini_path(path):
        return "gemini"
    if _is_codex_content(path):
        return "codex"
    if _is_gemini_content(path):
        return "gemini"
    return "claude"


def find_transcript(session_id: str, agent: str = "claude") -> Path | None:
    """Search for the session transcript JSONL file.

    Looks in agent-specific locations:
      - claude: ``~/.claude/projects/**/{session_id}.jsonl``
      - codex:  ``~/.codex/sessions/**/*{session_id}*.jsonl``
      - gemini: ``~/.gemini/tmp/**/chats/*.jsonl`` — scanned by content because
                filenames are timestamp-based, not UUID-based.

    Args:
        session_id: session UUID.
        agent: agent identifier (``"claude"``, ``"codex"``, or ``"gemini"``).

    Returns:
        Path to the .jsonl file, or None if not found.
    """
    if agent == "gemini":
        return _find_gemini_transcript(session_id)

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


def _find_gemini_transcript(session_id: str) -> Path | None:
    """Scan Gemini chat directory for a file matching the given session_id.

    Gemini stores chats at ``~/.gemini/tmp/<project_hash>/chats/`` with
    timestamp-based filenames. Finding by session_id requires reading the
    first line of each file to check ``session_metadata.sessionId``.

    Args:
        session_id: session UUID from Gemini's session_metadata.

    Returns:
        Path to the matching ``.jsonl`` file, or None if not found.
    """
    base = Path.home() / ".gemini" / "tmp"
    if not base.exists():
        return None
    for pattern in ("**/chats/*.jsonl", "**/chats/*.json"):
        for path in sorted(base.glob(pattern), key=lambda p: p.stat().st_mtime, reverse=True):
            if _gemini_session_matches(path, session_id):
                return path
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

    if fmt == "gemini":
        from .gemini_session_loader import parse_gemini_jsonl
        return parse_gemini_jsonl(path)

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
