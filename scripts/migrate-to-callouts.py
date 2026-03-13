#!/usr/bin/env python3
# @ai-generated(solo)
"""Migrate session transcripts from div-based format to Obsidian callout format.

Converts:
  <div class="chat-msg" data-role="human" ...>    →  > [!human]
  <div class="chat-msg" data-role="assistant" ...> →  > [!assistant]
  <div class="chat-refs">                         →  > ---
                                                      > *Refs:* ...

Content inside divs gets `> ` prefix for callout body.
**Human:**/**Assistant:** labels are stripped (callout type replaces them).
"""

import re
import sys
from pathlib import Path

# Matches opening div tags for chat-msg and chat-refs
_CHAT_MSG_RE = re.compile(
    r'<div class="chat-msg" data-role="(human|assistant)"'
    r'(?:\s+data-ts="([^"]*)")?'
    r"\s*>"
)
_CHAT_REFS_RE = re.compile(r'<div class="chat-refs">')
_CLOSE_DIV = "</div>"
_LABEL_RE = re.compile(r"^\*\*(Human|Assistant):\*\*$")


def _blockquote(line: str) -> str:
    """Add `> ` prefix to a line, or bare `>` for empty lines."""
    return f"> {line}" if line else ">"


def migrate_transcript(text: str) -> str:
    """Convert div-based transcript section to callout format.

    Args:
        text: full file content

    Returns:
        Converted file content.
    """
    # Split at ## Transcript to only process that section
    marker = "\n## Transcript\n"
    idx = text.find(marker)
    if idx == -1:
        return text  # no transcript section

    header = text[: idx + len(marker)]
    transcript = text[idx + len(marker) :]

    lines = transcript.split("\n")
    output: list[str] = []
    i = 0
    in_block = False
    block_type: str | None = None  # "human", "assistant", or "refs"
    block_ts: str | None = None
    block_lines: list[str] = []
    pending_refs: list[str] = []

    def flush_block() -> None:
        nonlocal block_lines, block_type, block_ts, in_block, pending_refs

        if not block_type:
            return

        if block_type == "refs":
            # Extract the refs content (strip `> ` prefix if present)
            ref_content = []
            for bl in block_lines:
                stripped = bl.strip()
                if stripped.startswith("> "):
                    stripped = stripped[2:]
                if stripped:
                    ref_content.append(stripped)
            if ref_content:
                # Attach to previous callout
                refs_text = " ".join(ref_content)
                output.append("> ---")
                output.append(f"> *Refs:* {refs_text}")
        else:
            # Filter out **Human:**/**Assistant:** labels and leading/trailing blanks
            content_lines = []
            for bl in block_lines:
                if _LABEL_RE.match(bl.strip()):
                    continue
                content_lines.append(bl)

            # Strip leading/trailing empty lines
            while content_lines and not content_lines[0].strip():
                content_lines.pop(0)
            while content_lines and not content_lines[-1].strip():
                content_lines.pop()

            if output:
                output.append("")  # blank line between callouts

            # Format timestamp for callout title (e.g. "14:30")
            ts_label = ""
            if block_ts:
                ts_match = re.search(r"T(\d{2}:\d{2})", block_ts)
                if ts_match:
                    ts_label = f" {ts_match.group(1)}"

            output.append(f"> [!{block_type}]{ts_label}")
            for cl in content_lines:
                output.append(_blockquote(cl))

        block_lines = []
        block_type = None
        block_ts = None
        in_block = False

    while i < len(lines):
        line = lines[i]

        # Check for chat-msg opening
        msg_match = _CHAT_MSG_RE.search(line)
        if msg_match:
            flush_block()
            block_type = msg_match.group(1)
            block_ts = msg_match.group(2)
            in_block = True
            block_lines = []
            i += 1
            continue

        # Check for chat-refs opening
        refs_match = _CHAT_REFS_RE.search(line)
        if refs_match:
            flush_block()
            block_type = "refs"
            in_block = True
            block_lines = []
            i += 1
            continue

        # Check for closing div
        if in_block and line.strip() == _CLOSE_DIV:
            flush_block()
            i += 1
            continue

        if in_block:
            block_lines.append(line)
        else:
            # Lines outside blocks (shouldn't be many in transcript)
            output.append(line)

        i += 1

    flush_block()

    result = header + "\n".join(output)
    # Clean up trailing whitespace
    return result.rstrip() + "\n"


def main() -> None:
    vault = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/Users/danil/Documents/Obsidian Vault/ai-memory")

    files = list(vault.rglob("sessions/**/*.md"))
    converted = 0
    skipped = 0

    for f in files:
        content = f.read_text(encoding="utf-8")
        if '<div class="chat-msg"' not in content:
            skipped += 1
            continue

        new_content = migrate_transcript(content)
        if new_content != content:
            f.write_text(new_content, encoding="utf-8")
            converted += 1
            print(f"  converted: {f.name}")
        else:
            skipped += 1

    print(f"\nDone: {converted} converted, {skipped} skipped")


if __name__ == "__main__":
    main()
