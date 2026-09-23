# @ai-generated(solo)
from __future__ import annotations
"""Generate an evaluation set: natural-language queries with a known target file.

For each sampled record an LLM writes queries a person would actually type to
find it again. The source file is the ground-truth answer.

The prompt forces recall-style paraphrase over term copying: a query that
reuses the document's distinctive wording measures lexical overlap, not
retrieval quality, and inflates every score downstream.

Usage:
  python3 eval/gen_queries.py --n 200 --out eval/data/queries.jsonl
"""

import argparse
import json
import os
import random
import re
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from corpus import Record, load_corpus  # noqa: E402

PLUGIN_DIR = Path(__file__).resolve().parent.parent / "plugins" / "ai-memory"
if str(PLUGIN_DIR) not in sys.path:
    sys.path.insert(0, str(PLUGIN_DIR))

from lib import storage  # noqa: E402

_URL = "https://api.openai.com/v1/chat/completions"

PROMPT = """You are building a retrieval benchmark for a developer's personal memory vault.

Below is one stored note. Write {n} search queries that this developer would \
plausibly type weeks later, from memory, to find this note again.

Hard rules — violating them makes the benchmark useless:
- Write from FADED memory. The developer recalls the gist, not the wording.
- Do NOT reuse distinctive words from the note (identifiers, file names, error \
strings, library names, rare terms). Paraphrase them into everyday language. \
Common words unavoidable to state the topic are fine.
- No quotes from the note.
- Each query stands alone: no "this note", "the above", no pronouns referring here.
- Vary the angle: one by the problem faced, one by the outcome or decision.
- 4 to 14 words. Lowercase. No trailing punctuation.
- Mirror how a developer types: terse, fragmentary, sometimes Russian if the \
note is Russian.
- If the note is too thin or generic to identify (a stub, a bare heading, \
boilerplate), return an empty list instead of inventing content.

Return strict JSON: {{"queries": ["...", "..."]}}

--- NOTE ---
title: {title}
tags: {tags}

{body}
--- END NOTE ---"""


def _api_key() -> str | None:
    """Read the OpenAI key from env, falling back to Claude Code settings."""
    key = os.environ.get("OPENAI_API_KEY")
    if key:
        return key
    try:
        settings = json.loads((Path.home() / ".claude" / "settings.json").read_text())
        return settings.get("env", {}).get("OPENAI_API_KEY")
    except Exception:
        return None


def _chat(key: str, model: str, prompt: str) -> str | None:
    """Single chat completion returning raw content, or None on failure."""
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "response_format": {"type": "json_object"},
        "temperature": 1.0,
    }).encode()
    req = urllib.request.Request(
        _URL, data=body,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            return json.loads(resp.read())["choices"][0]["message"]["content"]
    except Exception as exc:
        print(f"  ! api: {exc}", file=sys.stderr)
        return None


_WORD = re.compile(r"[a-zа-я0-9_]{4,}", re.I)


def leak_ratio(query: str, record: Record) -> float:
    """Fraction of the query's content words that appear verbatim in the note.

    A high ratio means the query copied the note's wording, so a hit proves
    lexical overlap rather than retrieval quality. Reported per query so the
    scorer can measure how much of the score rests on leaked terms.
    """
    qwords = {w.lower() for w in _WORD.findall(query)}
    if not qwords:
        return 0.0
    doc = set(w.lower() for w in _WORD.findall(f"{record.title} {record.text}"))
    return len(qwords & doc) / len(qwords)


def build(record: Record, key: str, model: str, per_doc: int) -> list[dict]:
    """Generate queries for one record; returns [] when the LLM declines."""
    body = record.body[:2500] if record.body.strip() else record.text[:2500]
    prompt = PROMPT.format(
        n=per_doc,
        title=record.title or Path(record.rel).stem,
        tags=", ".join(record.tags[:12]),
        body=body,
    )
    raw = _chat(key, model, prompt)
    if not raw:
        return []
    try:
        queries = json.loads(raw).get("queries", [])
    except Exception:
        return []

    out = []
    for q in queries:
        if not isinstance(q, str) or not (3 <= len(q.split()) <= 20):
            continue
        out.append({
            "query": q.strip(),
            "target": record.rel,
            "kind": record.kind,
            "tags": record.tags,
            "leak": round(leak_ratio(q, record), 3),
        })
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=200, help="documents to sample")
    ap.add_argument("--per-doc", type=int, default=2, help="queries per document")
    ap.add_argument("--model", default="gpt-4.1-mini")
    ap.add_argument("--seed", type=int, default=13)
    ap.add_argument("--min-chars", type=int, default=200,
                    help="skip notes whose indexed text is shorter (stubs)")
    ap.add_argument("--out", default="eval/data/queries.jsonl")
    args = ap.parse_args()

    key = _api_key()
    if not key:
        sys.exit("OPENAI_API_KEY not found in env or ~/.claude/settings.json")

    base = storage.get_base_dir()
    corpus = load_corpus(base)
    eligible = [r for r in corpus if len(r.text) >= args.min_chars]
    print(f"corpus {len(corpus)}, eligible {len(eligible)}")

    random.seed(args.seed)
    sample = random.sample(eligible, min(args.n, len(eligible)))

    rows: list[dict] = []
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(build, r, key, args.model, args.per_doc) for r in sample]
        for i, fut in enumerate(futures, 1):
            rows.extend(fut.result())
            if i % 25 == 0:
                print(f"  {i}/{len(sample)} docs -> {len(rows)} queries")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")

    leaks = [r["leak"] for r in rows]
    print(f"\nwrote {len(rows)} queries over {len({r['target'] for r in rows})} docs -> {out}")
    if leaks:
        hi = sum(1 for x in leaks if x > 0.6)
        print(f"leak: mean={sum(leaks)/len(leaks):.2f}  >0.6: {hi} ({hi/len(leaks):.0%})")


if __name__ == "__main__":
    main()
