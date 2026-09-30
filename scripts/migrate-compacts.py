#!/usr/bin/env python3
# @ai-generated(solo)
"""Move inline Claude Code compact summaries out of session transcripts.

Before the compacts sidecar existed, session-sync rendered each compact
summary as a ``[!human]`` callout and each ``/compact`` command as another.
This rewrites those files from their markdown alone (the source JSONL may be
gone): summaries go to ``<stem>.compacts.md``, the transcript keeps a marker,
``/compact`` callouts are dropped.  Trigger and token count are not in the
markdown, so migrated markers carry only the time.

Usage: migrate-compacts.py [--apply] [vault_dir]   (dry run by default)
"""
from __future__ import annotations

import argparse
import importlib.util
import os
import re
import sys
from pathlib import Path

_PLUGIN_ROOT = Path(__file__).resolve().parent.parent / "plugins" / "ai-memory"
sys.path.insert(0, str(_PLUGIN_ROOT))

from lib import storage  # noqa: E402


def _load_session_sync():
    spec = importlib.util.spec_from_file_location(
        "session_sync", _PLUGIN_ROOT / "hooks" / "scripts" / "session-sync.py",
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


ss = _load_session_sync()

_TRANSCRIPT_MARKER = "\n## Transcript\n"
_CALLOUT_HEAD_RE = re.compile(r"^> \[!(\w+)\] \*\*.+?\*\*(?: (\d{2}:\d{2}))?$")
_SUMMARY_PREFIX = "This session is being continued from a previous conversation"


class MigrationError(Exception):
    pass


def _unquote(lines: list[str]) -> str:
    out = []
    for line in lines:
        if line.startswith("> "):
            out.append(line[2:])
        elif line == ">":
            out.append("")
        else:
            out.append(line[1:])
    return "\n".join(out).strip()


def _split_blocks(transcript: str) -> list[list[str]]:
    """Split into callouts (runs of '>' lines) and other single lines."""
    blocks: list[list[str]] = []
    for line in transcript.split("\n"):
        if _CALLOUT_HEAD_RE.match(line):
            blocks.append([line])
        elif line.startswith(">") and blocks and blocks[-1][0].startswith("> [!"):
            blocks[-1].append(line)
        else:
            blocks.append([line])
    return blocks


def migrate_text(text: str, stem: str) -> tuple[str, list[dict], int]:
    """Return (new text, compact items, dropped command count)."""
    idx = text.find(_TRANSCRIPT_MARKER)
    if idx == -1:
        return text, [], 0
    head, transcript = text[: idx + len(_TRANSCRIPT_MARKER)], text[idx + len(_TRANSCRIPT_MARKER):]

    out: list[str] = []
    compacts: list[dict] = []
    dropped = 0
    for block in _split_blocks(transcript):
        m = _CALLOUT_HEAD_RE.match(block[0])
        if not m or m.group(1) != "human":
            out.extend(block)
            continue
        body = _unquote(block[2:] if len(block) > 1 and block[1] == ">" else block[1:])
        is_summary = body.startswith(_SUMMARY_PREFIX)
        if not is_summary and not ss._COMPACT_COMMAND_RE.match(body):
            out.extend(block)
            continue
        if "\n*Refs:* " in body:
            # Refs belong to the previous assistant turn; moving them is not worth guessing.
            raise MigrationError(f"refs attached to a compact callout in {stem}")
        if is_summary:
            item = {
                "kind": "compact",
                "index": len(compacts) + 1,
                "trigger": None,
                "pre_tokens": None,
                # Only HH:MM is in the callout header; _compact_meta reads "THH:MM".
                "timestamp": f"T{m.group(2)}" if m.group(2) else "",
                "summary": body,
            }
            compacts.append(item)
            meta = ss._compact_meta(item)
            n = item["index"]
            out.append(
                f"*Context compacted{' · ' + meta if meta else ''}*"
                f" — [[{stem}.compacts#Compact {n}|summary]] ^{ss.compact_block_id(n)}"
            )
        else:
            dropped += 1
            # Drop the separator before the callout; the one after it stays.
            if out and out[-1] == "":
                out.pop()

    return head + "\n".join(out), compacts, dropped


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("vault", nargs="?", default=os.path.expanduser(
        os.environ.get("AI_MEMORY_DIR", "~/.claude/ai-memory")))
    parser.add_argument("--apply", action="store_true", help="write changes (default: dry run)")
    args = parser.parse_args()

    base = Path(args.vault).expanduser()
    changed = failed = 0
    for path in sorted(base.rglob("*.md")):
        if storage.is_sidecar_file(path.name) or "sessions" not in path.parts:
            continue
        text = path.read_text(encoding="utf-8")
        try:
            new_text, compacts, dropped = migrate_text(text, path.stem)
        except MigrationError as exc:
            print(f"SKIP {exc}", file=sys.stderr)
            failed += 1
            continue
        if new_text == text:
            continue
        sidecar = path.with_name(path.stem + storage.COMPACTS_SUFFIX)
        if compacts and sidecar.exists():
            print(f"SKIP sidecar already exists: {sidecar}", file=sys.stderr)
            failed += 1
            continue
        changed += 1
        print(f"{path.relative_to(base)}: {len(compacts)} compacts, {dropped} /compact dropped, "
              f"transcript -{len(text) - len(new_text)} chars")
        if args.apply:
            if compacts:
                sidecar.write_text(ss.format_compacts_md(compacts, path.stem), encoding="utf-8")
            path.write_text(new_text, encoding="utf-8")

    mode = "migrated" if args.apply else "would migrate"
    print(f"{mode} {changed} files, {failed} skipped")
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
