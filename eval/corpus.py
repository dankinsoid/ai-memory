# @ai-generated(solo)
from __future__ import annotations
"""Build the evaluation corpus from the live memory vault.

Mirrors ``storage.reindex()`` exactly: each record carries the same text
that would be embedded in production, so a retrieval score here reflects
the real index rather than an idealised one.

Public API:
  load_corpus(base) -> list[Record]
"""

import sys
from dataclasses import dataclass, field
from pathlib import Path

PLUGIN_DIR = Path(__file__).resolve().parent.parent / "plugins" / "ai-memory"
if str(PLUGIN_DIR) not in sys.path:
    sys.path.insert(0, str(PLUGIN_DIR))

from lib import storage  # noqa: E402


@dataclass
class Record:
    """One indexable memory file.

    Attributes:
        rel:        path relative to the vault root — the vector store id
        kind:       "fact" or "session"
        text:       the exact text production would embed
        tags:       resolved tags (path-derived + front-matter)
        title:      session title, empty for facts
        body:       full body text, used for query generation
    """
    rel: str
    kind: str
    text: str
    tags: list[str] = field(default_factory=list)
    title: str = ""
    body: str = ""


def load_corpus(base: Path) -> list[Record]:
    """Collect every record ``reindex()`` would embed, in the same form.

    Args:
        base: vault root directory

    Returns:
        List of Record, facts first then sessions.
    """
    records: list[Record] = []

    for md_file in base.rglob("*.md"):
        rel_parts = md_file.relative_to(base).parts
        if storage._is_session_file(rel_parts):
            continue
        content = storage._read_content(md_file)
        if content is None:
            continue
        body = storage._body_text(content)
        if not body.strip():
            continue
        rel = str(md_file.relative_to(base))
        records.append(Record(
            rel=rel,
            kind="fact",
            text=storage.first_paragraph(body),
            tags=storage.all_tags_for_file(md_file, base, content),
            body=body,
        ))

    for sessions_dir in storage._all_session_dirs(base):
        for f in sessions_dir.rglob("*.md"):
            if storage._is_messages_file(f.name):
                continue
            rec = storage._read_session_file(f, base)
            if rec is None:
                continue
            title = rec.get("title", "")
            summary = rec.get("summary", "")
            compact = storage._extract_compact_text(rec.get("content", ""))
            text = f"{title}. {summary}"
            if compact:
                text += f"\n{compact}"
            if not text.strip():
                continue
            tags = rec.get("tags", [])
            if "session" not in tags:
                tags = list(tags) + ["session"]
            records.append(Record(
                rel=str(f.relative_to(base)),
                kind="session",
                text=text,
                tags=tags,
                title=title,
                body=summary or compact,
            ))

    return records


if __name__ == "__main__":
    base = storage.get_base_dir()
    recs = load_corpus(base)
    facts = sum(1 for r in recs if r.kind == "fact")
    lens = sorted(len(r.text) for r in recs)
    print(f"base:     {base}")
    print(f"records:  {len(recs)}  (facts {facts}, sessions {len(recs) - facts})")
    print(f"text len: p50={lens[len(lens)//2]}  p90={lens[int(len(lens)*0.9)]}  max={lens[-1]}")
