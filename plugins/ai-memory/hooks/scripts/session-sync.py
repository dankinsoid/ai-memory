#!/usr/bin/env python3
from __future__ import annotations
# @ai-generated(solo)
# Stop hook: appends conversation transcript to the session summary file.
#
# On every Stop, reads the JSONL transcript and appends a ## Transcript
# section to the session summary .md file. If no summary file exists yet
# (agent never called memory_session), creates a minimal one.
#
# Rewrites the transcript section on each run — no delta tracking.
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


def _git_user_name(cwd: str) -> str | None:
    """Return git user.name, or None if not configured.

    Args:
        cwd: working directory inside a git repo

    Returns:
        User name string, or None.
    """
    if not cwd:
        return None
    try:
        r = subprocess.run(
            ["git", "-C", cwd, "config", "user.name"],
            capture_output=True, text=True,
        )
        if r.returncode == 0:
            return r.stdout.strip() or None
    except Exception:
        pass
    return None


def _system_user_name() -> str | None:
    """Return OS username as fallback.

    Returns:
        Username string, or None.
    """
    import getpass
    try:
        return getpass.getuser() or None
    except Exception:
        return None


def _git_head_short(cwd: str) -> str | None:
    """Return short SHA of HEAD, or None if not a git repo.

    Args:
        cwd: working directory inside a git repo

    Returns:
        Short commit SHA string, or None.
    """
    if not cwd:
        return None
    try:
        r = subprocess.run(
            ["git", "-C", cwd, "rev-parse", "--short", "HEAD"],
            capture_output=True, text=True,
        )
        if r.returncode == 0:
            return r.stdout.strip()
    except Exception:
        pass
    return None


def _load_git_context(session_id: str) -> dict:
    """Load git context (branch, commit_start) saved by session-start hook.

    Args:
        session_id: current session UUID

    Returns:
        Dict with optional 'branch' and 'commit_start' keys.
    """
    try:
        from lib.db import get_state
        raw = get_state(f"git-context-{session_id}")
        if raw:
            return json.loads(raw)
    except Exception:
        pass
    return {}


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


def _strip_system_tags(text: str) -> str:
    """Remove system-injected XML tags (ide_opened_file, system-reminder, etc.).

    These tags are VSCode/Claude Code noise that shouldn't appear in session
    transcripts.

    Args:
        text: raw text possibly containing system XML tags

    Returns:
        Text with system tags removed and excess blank lines collapsed.
    """
    cleaned = _SYSTEM_TAG_RE.sub("", text)
    # Collapse runs of 3+ newlines left after removal
    cleaned = re.sub(r"\n{3,}", "\n\n", cleaned)
    return cleaned


def _extract_text(content) -> str:
    """Extract plain text from message content (str or list of content blocks).

    Skips tool_use, tool_result, and image blocks — only text blocks are included.
    Strips system-injected XML tags (ide_opened_file, system-reminder, etc.).

    Args:
        content: str or list of Anthropic content blocks

    Returns:
        Concatenated plain text string.
    """
    if isinstance(content, str):
        return _strip_system_tags(content)
    if isinstance(content, list):
        parts = []
        for block in content:
            if isinstance(block, dict) and block.get("type") == "text":
                parts.append(block.get("text", ""))
        return _strip_system_tags("\n".join(parts))
    return ""


_AI_MEMORY_TOOL_PREFIX = "mcp__plugin_ai-memory_ai-memory__"

# ---------------------------------------------------------------------------
# Agent identity registry
# ---------------------------------------------------------------------------

# Agent name → (callout_type, display_name).
_AGENT_REGISTRY: dict[str, tuple[str, str]] = {
    "claude":  ("claude",  "Claude"),
    "codex":   ("codex",   "Codex"),
    "chatgpt": ("chatgpt", "ChatGPT"),
    "gemini":  ("gemini",  "Gemini"),
}
_DEFAULT_AGENT = ("assistant", "Bot")


def _resolve_agent(agent: str | None) -> tuple[str, str]:
    """Resolve agent name to (callout_type, display_name).

    Args:
        agent: agent identifier like 'claude', 'codex', etc.

    Returns:
        Tuple of (callout_type, display_name) for rendering.
    """
    if not agent:
        return _DEFAULT_AGENT
    return _AGENT_REGISTRY.get(agent.lower(), _DEFAULT_AGENT)
_WIKILINK_RE = re.compile(r"\[\[([^\]]+)\]\]")
# System-injected XML tags that are noise in session transcripts.
# Uses re.DOTALL so the pattern spans multiple lines within a single tag.
_SYSTEM_TAG_RE = re.compile(
    r"<(?:ide_opened_file|ide_selection|system-reminder|available-deferred-tools)"
    r"[^>]*>.*?</(?:ide_opened_file|ide_selection|system-reminder|available-deferred-tools)>",
    re.DOTALL,
)
# Matches markdown links [text](target) where target is NOT an http(s) URL.
# These are typically code file references that pollute Obsidian's graph
# by creating phantom nodes. Convert to `text` to neutralize.
_MD_FILE_LINK_RE = re.compile(r"\[([^\]]+)\]\((?!https?://)([^)]+)\)")


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


def _neutralize_file_links(text: str) -> str:
    """Replace markdown file-path links with backtick-quoted text.

    Converts ``[name](path)`` → `` `name` `` for non-URL targets so Obsidian
    doesn't create phantom graph nodes. HTTP(S) links are left intact.

    Args:
        text: raw markdown text

    Returns:
        Text with file-path links neutralized.
    """
    return _MD_FILE_LINK_RE.sub(r"`\1`", text)


def _blockquote(text: str) -> str:
    """Prefix every line with ``> `` for markdown blockquote/callout body.

    Empty lines become ``>`` (bare prefix) to preserve paragraph breaks
    inside the callout.

    Args:
        text: plain or markdown text

    Returns:
        Blockquoted text.
    """
    return "\n".join(
        f"> {line}" if line else ">" for line in text.splitlines()
    )


def format_messages_md(
    stream: list[dict],
    agent: str | None = None,
    user_name: str | None = None,
) -> str:
    """Format a message stream as Obsidian callout-based chat transcript.

    Each message becomes a ``> [!human]`` or ``> [!claude]`` (etc.) callout.
    The assistant callout type and emoji are determined by the ``agent``
    parameter via ``_resolve_agent``.

    Obsidian renders callouts as single container divs, so no JS re-parenting
    is needed — pure CSS snippet handles bubble styling.

    Memory references are appended to the preceding assistant message
    as a ``---`` + *Refs:* line inside the same callout.

    In non-Obsidian markdown viewers, callouts degrade to blockquotes —
    readable, just not styled as bubbles.

    Args:
        stream: ordered list of items from extract_message_stream
        agent: agent identifier (e.g. 'claude', 'codex'); defaults to 'claude'

    Returns:
        Markdown string.
    """
    agent_callout, agent_label = _resolve_agent(agent or "claude")
    human_label = user_name or "You"
    lines: list[str] = []
    # Track pending refs to attach to the preceding assistant callout
    pending_refs: list[str] = []

    for item in stream:
        if item["kind"] == "refs":
            pending_refs.extend(item["refs"])
        elif item["kind"] == "message":
            is_human = item["role"] == "user"
            callout_type = "human" if is_human else agent_callout
            label = human_label if is_human else agent_label
            text = _neutralize_file_links(item["text"])

            # Attach pending refs to the previous assistant message
            # (refs always follow the assistant turn that triggered them)
            if pending_refs and lines:
                refs_str = ", ".join(f"[[{r}]]" for r in pending_refs)
                lines.append(f"> ---")
                lines.append(f"> *Refs:* {refs_str}")
                pending_refs.clear()

            if lines:
                lines.append("")  # blank line between callouts

            # Format timestamp as HH:MM for callout title
            ts_label = ""
            ts = item.get("timestamp", "")
            if ts:
                ts_match = re.search(r"T(\d{2}:\d{2})", ts)
                if ts_match:
                    ts_label = f" {ts_match.group(1)}"

            lines.append(f"> [!{callout_type}] **{label}**{ts_label}")
            lines.append(">")
            lines.append(_blockquote(text))

    # Flush any remaining refs
    if pending_refs and lines:
        refs_str = ", ".join(f"[[{r}]]" for r in pending_refs)
        lines.append(f"> ---")
        lines.append(f"> *Refs:* {refs_str}")

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
# Transcript merging
# ---------------------------------------------------------------------------


_GIT_FM_FIELDS = ("branch", "commit_start", "commit_end")


def _update_git_fields(
    summary_path: Path, git_ctx: dict, commit_end: str | None
) -> None:
    """Add or update git fields in session frontmatter.

    Only writes fields that are missing in the existing frontmatter.
    commit_end is always updated to reflect the latest state.

    Args:
        summary_path: path to the session summary .md file
        git_ctx: dict with optional 'branch' and 'commit_start' keys
        commit_end: short SHA of current HEAD (may be None)
    """
    content = summary_path.read_text(encoding="utf-8")
    fm = parse_front_matter(content)

    updates: dict[str, str] = {}
    if git_ctx.get("branch") and not fm.get("branch"):
        updates["branch"] = git_ctx["branch"]
    if git_ctx.get("commit_start") and not fm.get("commit_start"):
        updates["commit_start"] = git_ctx["commit_start"]
    if commit_end:
        updates["commit_end"] = commit_end

    if not updates:
        return

    # Insert git fields before closing ---
    lines = content.split("\n")
    # Find the closing --- of frontmatter (second occurrence)
    fm_end = None
    found_first = False
    for i, line in enumerate(lines):
        if line.strip() == "---":
            if found_first:
                fm_end = i
                break
            found_first = True

    if fm_end is None:
        return  # malformed frontmatter, skip

    # Remove existing git lines to avoid duplicates, then re-insert
    new_lines = []
    for i, line in enumerate(lines):
        if i > 0 and i < fm_end:
            key = line.split(":")[0].strip() if ":" in line else ""
            if key in _GIT_FM_FIELDS:
                continue
        new_lines.append(line)

    # Recalculate fm_end after removals
    fm_end_new = None
    found_first = False
    for i, line in enumerate(new_lines):
        if line.strip() == "---":
            if found_first:
                fm_end_new = i
                break
            found_first = True

    if fm_end_new is None:
        return

    # Insert all git fields before closing ---
    insert_lines = [f"{k}: {v}" for k, v in updates.items()]
    # Also re-add existing git fields that weren't updated
    for field in _GIT_FM_FIELDS:
        if field not in updates and fm.get(field):
            insert_lines.append(f"{field}: {fm[field]}")

    # Sort: branch, commit_start, commit_end
    field_order = {f: i for i, f in enumerate(_GIT_FM_FIELDS)}
    insert_lines.sort(key=lambda l: field_order.get(l.split(":")[0], 99))

    for j, il in enumerate(insert_lines):
        new_lines.insert(fm_end_new + j, il)

    summary_path.write_text("\n".join(new_lines), encoding="utf-8")


_TRANSCRIPT_HEADER = "## Transcript"


def _replace_transcript_section(summary_path: Path, transcript_md: str) -> None:
    """Replace (or append) the ## Transcript section in a session summary file.

    Everything from ``## Transcript`` to end-of-file is replaced with the new
    transcript content. A ``---`` divider separates the transcript from the
    summary/compact sections above. If no transcript section exists yet, it is
    appended.

    Args:
        summary_path: path to the session summary .md file
        transcript_md: formatted transcript markdown (without the header)
    """
    content = summary_path.read_text(encoding="utf-8")
    marker = f"\n{_TRANSCRIPT_HEADER}\n"
    idx = content.find(marker)
    if idx != -1:
        # Strip preceding divider too (it's part of the transcript block)
        base = content[:idx].rstrip().removesuffix("---").rstrip()
    else:
        base = content.rstrip()

    # Strip any leftover [[...messages]] wikilinks from old format
    base = re.sub(r"\n*\[\[[^\]]+\.messages\]\]\s*$", "", base).rstrip()

    new_content = base + f"\n\n---\n\n{_TRANSCRIPT_HEADER}\n\n{transcript_md}\n"
    summary_path.write_text(new_content, encoding="utf-8")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    data = json.loads(sys.stdin.read())
    session_id = data.get("session_id")
    cwd = data.get("cwd", "")
    agent = data.get("agent", "claude")

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

    # Resolve git context: start info from state DB, current commit from git
    git_ctx = _load_git_context(session_id) if session_id else {}
    commit_end = _git_head_short(cwd)
    user_name = _git_user_name(cwd) or _system_user_name()

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
            branch=git_ctx.get("branch"),
            commit_start=git_ctx.get("commit_start"),
            commit_end=commit_end,
        )
        existing = storage._find_session_file(sessions_parent, session_id)
    else:
        # Update git fields in existing session frontmatter
        _update_git_fields(existing, git_ctx, commit_end)

    if existing is None:
        # Should not happen — upsert_session always creates the file
        sys.exit(1)

    # Append transcript section to the session summary file
    _replace_transcript_section(
        existing, format_messages_md(stream, agent=agent, user_name=user_name)
    )


if __name__ == "__main__":
    main()
