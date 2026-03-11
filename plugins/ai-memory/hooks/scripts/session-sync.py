#!/usr/bin/env python3
# @ai-generated(solo)
# Stop hook: saves full conversation transcript to messages.md.
#
# On every Stop, reads the JSONL transcript and writes a formatted markdown
# conversation to {date} {title} messages.md alongside the session summary file.
# If no summary file exists yet (agent never called memory_session), creates
# a minimal one with title derived from the first user message.
#
# Rewrites the full messages.md on each run — no delta tracking.
# Adds/updates the messages: wiki-link in the summary front-matter.
#
# Env-var toggles (set any to disable):
#   AI_MEMORY_DISABLED=1     — master switch (all hooks)
#   AI_MEMORY_NO_WRITE=1     — disable all writes
#   AI_MEMORY_NO_SESSIONS=1  — disable session-specific features

import json
import os
import re
import subprocess
import sys
from pathlib import Path

_HOOK_DIR = Path(__file__).resolve().parent
_PLUGIN_ROOT = _HOOK_DIR.parent.parent
sys.path.insert(0, str(_PLUGIN_ROOT))

from lib import storage  # noqa: E402
from lib.tags import parse_front_matter  # noqa: E402


# ---------------------------------------------------------------------------
# Git helpers
# ---------------------------------------------------------------------------


def git_project_name(cwd: str) -> str | None:
    """Extract repo name from git remote URL.

    Args:
        cwd: working directory to run git in

    Returns:
        Repo name string, or None if unavailable.
    """
    if not cwd:
        return None
    try:
        result = subprocess.run(
            ["git", "-C", cwd, "remote", "get-url", "origin"],
            capture_output=True, text=True
        )
        if result.returncode == 0:
            url = result.stdout.strip().rstrip("/").removesuffix(".git")
            return url.split("/")[-1].split(":")[-1]
    except Exception:
        pass
    return None


def derive_project(cwd: str) -> str | None:
    """Derive project name from git remote, falling back to directory name.

    Args:
        cwd: current working directory

    Returns:
        Project name string, or None.
    """
    return git_project_name(cwd) or (cwd.rstrip("/").split("/")[-1] if cwd else None)


# ---------------------------------------------------------------------------
# JSONL transcript parsing
# ---------------------------------------------------------------------------


def find_transcript(session_id: str) -> Path | None:
    """Search ~/.claude/projects recursively for the session JSONL file.

    Args:
        session_id: session UUID

    Returns:
        Path to the .jsonl file, or None if not found.
    """
    projects_dir = Path.home() / ".claude" / "projects"
    if not projects_dir.exists():
        return None
    matches = list(projects_dir.glob(f"**/{session_id}.jsonl"))
    return matches[0] if matches else None


def parse_jsonl(path: Path) -> list[dict]:
    """Parse a JSONL file, silently skipping malformed lines.

    Args:
        path: path to .jsonl file

    Returns:
        List of parsed JSON objects.
    """
    entries = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            entries.append(json.loads(line))
        except Exception:
            pass
    return entries


def _extract_text(content) -> str:
    """Extract plain text from message content (str or list of content blocks).

    Skips tool_use, tool_result, and image blocks — only text blocks are included.

    Args:
        content: str or list of Anthropic content blocks

    Returns:
        Concatenated plain text string.
    """
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for block in content:
            if isinstance(block, dict) and block.get("type") == "text":
                parts.append(block.get("text", ""))
        return "\n".join(parts)
    return ""


def extract_messages(entries: list[dict]) -> list[dict]:
    """Extract human-readable user/assistant turns from transcript entries.

    Skips meta messages and entries with no plain text (tool-only turns).

    Args:
        entries: parsed JSONL entries

    Returns:
        List of dicts with keys: role (str), text (str), timestamp (str).
    """
    result = []
    for e in entries:
        if e.get("type") not in ("user", "assistant"):
            continue
        if e.get("isMeta"):
            continue
        msg = e.get("message") or {}
        role = msg.get("role")
        if not role:
            continue
        text = _extract_text(msg.get("content", "")).strip()
        if not text:
            continue
        result.append({
            "role": role,
            "text": text,
            "timestamp": e.get("timestamp", ""),
        })
    return result


# ---------------------------------------------------------------------------
# Formatting
# ---------------------------------------------------------------------------


def format_messages_md(messages: list[dict]) -> str:
    """Format messages as readable markdown with Human/Assistant sections.

    Args:
        messages: list of dicts from extract_messages

    Returns:
        Markdown string.
    """
    lines = []
    for msg in messages:
        role = "Human" if msg["role"] == "user" else "Assistant"
        lines.append(f"## {role}")
        lines.append("")
        lines.append(msg["text"])
        lines.append("")
    return "\n".join(lines).strip()


def derive_title(messages: list[dict]) -> str:
    """Derive session title from first user message (first line, max 50 chars).

    Args:
        messages: list of dicts from extract_messages

    Returns:
        Title string.
    """
    for msg in messages:
        if msg["role"] == "user":
            first_line = msg["text"].splitlines()[0].strip() if msg["text"] else ""
            if len(first_line) > 50:
                first_line = first_line[:47] + "..."
            return first_line or "untitled session"
    return "untitled session"


# ---------------------------------------------------------------------------
# Summary front-matter update
# ---------------------------------------------------------------------------


def _update_messages_link(summary_path: Path, messages_stem: str) -> None:
    """Add or update messages: wiki-link in the summary file front-matter.

    No-op if the link is already present. Rewrites the file in place.

    Args:
        summary_path: path to the session summary .md file
        messages_stem: stem for the messages file (without .md)
    """
    content = summary_path.read_text(encoding="utf-8")
    link = f"[[{messages_stem}]]"

    # Already has messages: link — check if it needs updating
    if re.search(r"^messages:", content, re.MULTILINE):
        if link in content:
            return  # already correct
        content = re.sub(r"^messages:.*$", f"messages: {link}", content, flags=re.MULTILINE)
    else:
        # Insert before closing --- of front-matter
        content = re.sub(r"^---\s*$", f"messages: {link}\n---", content, count=1, flags=re.MULTILINE)
        # The above replaces the FIRST --- (opening), so we need to be smarter:
        # Find the second --- and insert before it.
        # Revert and do it properly.
        content = summary_path.read_text(encoding="utf-8")
        lines = content.splitlines(keepends=True)
        close_idx = None
        for i, line in enumerate(lines):
            if i == 0:
                continue  # skip opening ---
            if line.strip() == "---":
                close_idx = i
                break
        if close_idx is not None:
            lines.insert(close_idx, f"messages: {link}\n")
            content = "".join(lines)

    summary_path.write_text(content, encoding="utf-8")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    if any(os.environ.get(v) for v in ("AI_MEMORY_DISABLED", "AI_MEMORY_NO_WRITE", "AI_MEMORY_NO_SESSIONS")):
        sys.exit(0)

    data = json.loads(sys.stdin.read())
    session_id = data.get("session_id")
    cwd = data.get("cwd", "")

    if not session_id:
        sys.exit(0)

    transcript_path = data.get("transcript_path")
    transcript = Path(transcript_path) if transcript_path else find_transcript(session_id)
    if not transcript or not transcript.exists():
        sys.exit(0)

    entries = parse_jsonl(transcript)
    messages = extract_messages(entries)
    if not messages:
        sys.exit(0)

    project = derive_project(cwd)
    sessions_base = storage.get_sessions_base_dir()
    sessions_dir = (
        sessions_base / "projects" / project / "sessions"
        if project
        else sessions_base / "sessions"
    )

    # Find or create session summary file
    existing = storage._find_session_file(sessions_dir, session_id)

    if existing is None:
        # Agent never called memory_session — create minimal summary
        title = derive_title(messages)
        storage.upsert_session(
            session_id=session_id,
            project=project,
            title=title,
            summary="(auto-saved)",
            tags=[],
        )
        existing = storage._find_session_file(sessions_dir, session_id)

    if existing is None:
        # Should not happen — upsert_session always creates the file
        sys.exit(1)

    # Write full conversation transcript to messages.md
    messages_stem = f"{existing.stem}.messages"
    messages_path = existing.parent / f"{messages_stem}.md"
    messages_path.write_text(format_messages_md(messages), encoding="utf-8")

    # Ensure summary front-matter has the messages: wiki-link
    _update_messages_link(existing, messages_stem)


if __name__ == "__main__":
    main()
