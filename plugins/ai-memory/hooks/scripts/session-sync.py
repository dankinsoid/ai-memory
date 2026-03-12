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

import json
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


_AI_MEMORY_TOOL_PREFIX = "mcp__plugin_ai-memory_ai-memory__"
_WIKILINK_RE = re.compile(r"\[\[([^\]]+)\]\]")


def _extract_refs_from_tool_result(block: dict) -> list[str]:
    """Extract wikilink stems from a tool_result content block.

    Args:
        block: a tool_result content block

    Returns:
        List of wikilink stems found in the block.
    """
    result_content = block.get("content", "")
    if isinstance(result_content, str):
        text = result_content
    elif isinstance(result_content, list):
        text = " ".join(
            b.get("text", "") for b in result_content
            if isinstance(b, dict) and b.get("type") == "text"
        )
    else:
        return []
    return _WIKILINK_RE.findall(text)


def extract_message_stream(entries: list[dict]) -> list[dict]:
    """Extract an ordered stream of text messages and inline memory references.

    Produces items in transcript order. Each item is either:
    - {"kind": "message", "role": str, "text": str, "timestamp": str}
    - {"kind": "refs", "refs": list[str]}  (deduplicated wikilinks)

    Memory refs appear at the position in the stream where the tool_result
    was returned, so they can be rendered inline between messages.

    Args:
        entries: parsed JSONL entries

    Returns:
        Ordered list of stream items.
    """
    # Pre-scan: collect ai-memory tool_use IDs
    ai_memory_tool_ids: set[str] = set()
    for e in entries:
        msg = e.get("message") or {}
        content = msg.get("content")
        if not isinstance(content, list):
            continue
        for block in content:
            if not isinstance(block, dict):
                continue
            if (
                block.get("type") == "tool_use"
                and block.get("name", "").startswith(_AI_MEMORY_TOOL_PREFIX)
            ):
                tool_id = block.get("id")
                if tool_id:
                    ai_memory_tool_ids.add(tool_id)

    # Single pass: emit messages and inline refs in order
    seen_refs: set[str] = set()
    stream: list[dict] = []
    for e in entries:
        if e.get("type") not in ("user", "assistant"):
            continue
        if e.get("isMeta"):
            continue
        msg = e.get("message") or {}
        role = msg.get("role")
        if not role:
            continue
        content = msg.get("content", "")

        # Check for memory tool_result refs (in user entries carrying tool_result)
        if ai_memory_tool_ids and isinstance(content, list):
            new_refs: list[str] = []
            for block in content:
                if not isinstance(block, dict):
                    continue
                if (
                    block.get("type") == "tool_result"
                    and block.get("tool_use_id") in ai_memory_tool_ids
                ):
                    for ref in _extract_refs_from_tool_result(block):
                        if ref not in seen_refs:
                            seen_refs.add(ref)
                            new_refs.append(ref)
            if new_refs:
                stream.append({"kind": "refs", "refs": new_refs})

        # Emit text message if present
        text = _extract_text(content).strip()
        if text:
            stream.append({
                "kind": "message",
                "role": role,
                "text": text,
                "timestamp": e.get("timestamp", ""),
            })

    return stream


# ---------------------------------------------------------------------------
# Formatting
# ---------------------------------------------------------------------------


def format_messages_md(stream: list[dict]) -> str:
    """Format a message stream as readable markdown with inline memory references.

    Renders text messages as ## Human / ## Assistant sections. Memory references
    are inserted inline as blockquote lines at the position they appeared in the
    conversation (i.e. right after the tool returned results).

    Args:
        stream: ordered list of items from extract_message_stream

    Returns:
        Markdown string.
    """
    lines: list[str] = []
    for item in stream:
        if item["kind"] == "refs":
            refs_str = ", ".join(f"[[{r}]]" for r in item["refs"])
            lines.append(f"> Referenced: {refs_str}")
            lines.append("")
        elif item["kind"] == "message":
            role = "Human" if item["role"] == "user" else "Assistant"
            lines.append(f"## {role}")
            lines.append("")
            lines.append(item["text"])
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
    """Add messages wikilink to the summary file body (not front-matter).

    Appends ``[[messages_stem]]`` at the end of the file if not already present.
    No-op if the link is already in the file content.

    Args:
        summary_path: path to the session summary .md file
        messages_stem: stem for the messages file (without .md)
    """
    content = summary_path.read_text(encoding="utf-8")
    link = f"[[{messages_stem}]]"

    if link in content:
        return  # already present

    # Append wikilink at end of file body
    content = content.rstrip() + "\n\n" + link + "\n"
    summary_path.write_text(content, encoding="utf-8")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
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
    stream = extract_message_stream(entries)
    messages = [item for item in stream if item["kind"] == "message"]
    if not messages:
        sys.exit(0)

    project = derive_project(cwd)
    sessions_base = storage.get_sessions_base_dir()
    sessions_parent = (
        sessions_base / "projects" / project / "sessions"
        if project
        else sessions_base / "sessions"
    )

    # Find or create session summary file
    existing = storage._find_session_file(sessions_parent, session_id)

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
        existing = storage._find_session_file(sessions_parent, session_id)

    if existing is None:
        # Should not happen — upsert_session always creates the file
        sys.exit(1)

    # Write full conversation transcript to messages.md
    messages_stem = f"{existing.stem}.messages"
    messages_path = existing.parent / f"{messages_stem}.md"
    messages_path.write_text(format_messages_md(stream), encoding="utf-8")

    # Ensure summary front-matter has the messages: wiki-link
    _update_messages_link(existing, messages_stem)


if __name__ == "__main__":
    main()
