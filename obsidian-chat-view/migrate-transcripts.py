#!/usr/bin/env python3
# @ai-generated(solo)
"""Migrate old transcript formats to div-based chat format.

Handles two legacy formats:
  1. Turn format:  ## Turn N (timestamp)\n**role**\ncontent
  2. Heading format:  ## Human\ncontent\n## Assistant\ncontent

Rewrites the ## Transcript section in-place, preserving everything above it.

Usage:
    python3 migrate-transcripts.py <sessions-root>
    python3 migrate-transcripts.py  # defaults to AI_MEMORY_SESSIONS_DIR or ~/Documents/Obsidian Vault/ai-memory

Args:
    sessions-root: path containing projects/*/sessions/ or sessions/ subdirs
"""

from __future__ import annotations

import os
import re
import sys
from pathlib import Path


# ── Regexes for legacy formats ──────────────────────────────────────

# Turn format: ## Turn N (2026-03-09T07:49:28.548Z)
TURN_HEADER_RE = re.compile(r"^## Turn \d+ \(([^)]+)\)\s*$", re.MULTILINE)

# Heading format: ## Human or ## Assistant
HEADING_RE = re.compile(r"^## (Human|Assistant)\s*$", re.MULTILINE)

# Bold role line inside Turn: **user** or **assistant**
BOLD_ROLE_RE = re.compile(r"^\*\*(user|assistant)\*\*\s*$", re.MULTILINE)

# Detect new format (already migrated)
NEW_FORMAT_RE = re.compile(r'<div class="chat-msg"')


def parse_turn_format(transcript: str) -> list[dict]:
    """Parse Turn-based transcript into messages.

    Args:
        transcript: text after '## Transcript' header

    Returns:
        List of {role, text, timestamp} dicts.
    """
    messages: list[dict] = []
    lines = transcript.split("\n")
    i = 0
    current_ts = ""

    while i < len(lines):
        line = lines[i]

        # Match turn header — extract timestamp
        m = TURN_HEADER_RE.match(line)
        if m:
            current_ts = m.group(1)
            i += 1
            continue

        # Match bold role line
        m = BOLD_ROLE_RE.match(line)
        if m:
            role = m.group(1)  # "user" or "assistant"
            i += 1
            # Collect content until next bold role, turn header, or EOF
            content_lines: list[str] = []
            while i < len(lines):
                if TURN_HEADER_RE.match(lines[i]) or BOLD_ROLE_RE.match(lines[i]):
                    break
                content_lines.append(lines[i])
                i += 1
            text = "\n".join(content_lines).strip()
            if text:
                messages.append({
                    "role": role,
                    "text": text,
                    "timestamp": current_ts,
                })
            continue

        i += 1

    return messages


def parse_heading_format(transcript: str) -> list[dict]:
    """Parse ## Human / ## Assistant transcript into messages.

    Args:
        transcript: text after '## Transcript' header

    Returns:
        List of {role, text, timestamp} dicts.
    """
    messages: list[dict] = []
    lines = transcript.split("\n")
    i = 0

    while i < len(lines):
        line = lines[i]
        m = HEADING_RE.match(line)
        if m:
            role = "user" if m.group(1) == "Human" else "assistant"
            i += 1
            content_lines: list[str] = []
            while i < len(lines):
                if HEADING_RE.match(lines[i]):
                    break
                content_lines.append(lines[i])
                i += 1
            text = "\n".join(content_lines).strip()
            if text:
                messages.append({
                    "role": role,
                    "text": text,
                    "timestamp": "",
                })
            continue

        i += 1

    return messages


def format_new(messages: list[dict]) -> str:
    """Format messages in the new div-based chat format.

    Args:
        messages: list of {role, text, timestamp} dicts

    Returns:
        Formatted markdown string.
    """
    lines: list[str] = []
    for msg in messages:
        role = "human" if msg["role"] == "user" else "assistant"
        label = "Human" if role == "human" else "Assistant"
        ts = msg.get("timestamp", "")
        lines.append(f'<div class="chat-msg" data-role="{role}" data-ts="{ts}">')
        lines.append("")
        lines.append(f"**{label}:**")
        lines.append("")
        lines.append(msg["text"])
        lines.append("")
        lines.append("</div>")
        lines.append("")
    return "\n".join(lines).strip()


def migrate_file(path: Path) -> bool:
    """Migrate a single session file to the new transcript format.

    Args:
        path: path to the .md session file

    Returns:
        True if the file was modified, False if skipped.
    """
    content = path.read_text(encoding="utf-8")

    # Find ## Transcript section
    marker = "\n## Transcript\n"
    idx = content.find(marker)
    if idx == -1:
        return False

    transcript = content[idx + len(marker):]

    # Skip if already in new format
    if NEW_FORMAT_RE.search(transcript):
        return False

    # Detect format and parse
    if TURN_HEADER_RE.search(transcript):
        messages = parse_turn_format(transcript)
    elif HEADING_RE.search(transcript):
        messages = parse_heading_format(transcript)
    else:
        return False

    if not messages:
        return False

    # Rebuild file
    base = content[:idx].rstrip()
    # Strip preceding divider if present
    base = base.removesuffix("---").rstrip()
    new_transcript = format_new(messages)
    new_content = base + "\n\n---\n\n## Transcript\n\n" + new_transcript + "\n"

    path.write_text(new_content, encoding="utf-8")
    return True


def find_sessions_root() -> Path:
    """Resolve the sessions root directory.

    Returns:
        Path to the sessions root.
    """
    override = os.environ.get("AI_MEMORY_SESSIONS_DIR")
    if override:
        return Path(override).expanduser()

    # Default Obsidian vault location
    default = Path.home() / "Documents" / "Obsidian Vault" / "ai-memory"
    if default.exists():
        return default

    default2 = Path.home() / ".claude" / "ai-memory"
    if default2.exists():
        return default2

    print("Cannot find sessions directory. Pass path as argument or set AI_MEMORY_SESSIONS_DIR.")
    sys.exit(1)


def main() -> None:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else find_sessions_root()

    if not root.exists():
        print(f"Directory not found: {root}")
        sys.exit(1)

    session_files = list(root.rglob("sessions/**/*.md"))
    print(f"Found {len(session_files)} session files in {root}")

    migrated = 0
    skipped = 0
    for f in session_files:
        if migrate_file(f):
            migrated += 1
            print(f"  migrated: {f.name}")
        else:
            skipped += 1

    print(f"\nDone: {migrated} migrated, {skipped} skipped (already new format or no transcript)")


if __name__ == "__main__":
    main()
