# @ai-generated(solo)
from __future__ import annotations
"""Score retrieval over the generated query set.

Runs every query through one or more retrievers and reports recall@k and MRR.

Retrievers:
  semantic — the production path: OpenAI embeddings + cosine, as used by
             ``storage.search_facts(query=...)``. Needs OPENAI_API_KEY.
  bm25     — lexical baseline over the same indexed text, no API. Its purpose
             is to bound the semantic number: an embedding that fails to beat
             word matching is not earning its cost.

Usage:
  python3 eval/score.py --queries eval/data/queries.jsonl --retriever bm25
  python3 eval/score.py --retriever semantic,bm25
"""

import argparse
import json
import math
import os
import re
import sys
from collections import Counter
from pathlib import Path

from corpus import Record, load_corpus  # noqa: E402

PLUGIN_DIR = Path(__file__).resolve().parent.parent / "plugins" / "ai-memory"
if str(PLUGIN_DIR) not in sys.path:
    sys.path.insert(0, str(PLUGIN_DIR))

from lib import storage  # noqa: E402

KS = (1, 5, 10, 20)
_TOKEN = re.compile(r"[a-zа-я0-9_]+", re.I)


def tokenize(text: str) -> list[str]:
    return [t.lower() for t in _TOKEN.findall(text)]


class BM25:
    """Okapi BM25 over the corpus records' indexed text.

    Lexical floor for the benchmark, not a shipped code path.
    """

    def __init__(self, records: list[Record], k1: float = 1.5, b: float = 0.75):
        self.records = records
        self.k1, self.b = k1, b
        self.docs = [tokenize(f"{r.title} {r.text}") for r in records]
        self.len = [len(d) for d in self.docs]
        self.avglen = sum(self.len) / len(self.len) if self.len else 0.0
        self.tf = [Counter(d) for d in self.docs]
        df: Counter[str] = Counter()
        for d in self.docs:
            df.update(set(d))
        n = len(records)
        self.idf = {
            term: math.log(1 + (n - c + 0.5) / (c + 0.5))
            for term, c in df.items()
        }
        self.postings: dict[str, list[int]] = {}
        for i, d in enumerate(self.docs):
            for term in set(d):
                self.postings.setdefault(term, []).append(i)

    def search(self, query: str, top_k: int) -> list[tuple[str, float]]:
        scores: dict[int, float] = {}
        for term in tokenize(query):
            idf = self.idf.get(term)
            if idf is None:
                continue
            for i in self.postings[term]:
                freq = self.tf[i][term]
                denom = freq + self.k1 * (1 - self.b + self.b * self.len[i] / self.avglen)
                scores[i] = scores.get(i, 0.0) + idf * (freq * (self.k1 + 1)) / denom
        ranked = sorted(scores.items(), key=lambda kv: -kv[1])[:top_k]
        return [(self.records[i].rel, s) for i, s in ranked]


class Semantic:
    """Production semantic retrieval: embed the corpus once, cosine per query.

    Embeds the same text ``reindex()`` would send, via the same
    ``lib.embedding`` client, so the measured number is the shipped one.
    """

    def __init__(self, records: list[Record], cache: Path):
        from lib import embedding

        self.embedding = embedding
        self.records = records
        if not embedding.is_enabled():
            raise RuntimeError(
                "embedding disabled — set OPENAI_API_KEY and AI_MEMORY_EMBEDDING=true"
            )
        self.vectors = self._load_or_embed(cache)

    def _load_or_embed(self, cache: Path) -> list[list[float] | None]:
        """Embed the corpus, reusing a cached file keyed by record id."""
        cached: dict[str, list[float]] = {}
        if cache.exists():
            cached = json.loads(cache.read_text())
            print(f"  cache: {len(cached)} vectors")

        missing = [r for r in self.records if r.rel not in cached]
        for start in range(0, len(missing), 128):
            chunk = missing[start:start + 128]
            vecs = self.embedding.embed_batch([r.text for r in chunk])
            for rec, vec in zip(chunk, vecs):
                if vec is not None:
                    cached[rec.rel] = vec
            print(f"  embedded {min(start + 128, len(missing))}/{len(missing)}")

        if missing:
            cache.parent.mkdir(parents=True, exist_ok=True)
            cache.write_text(json.dumps(cached))

        return [cached.get(r.rel) for r in self.records]

    def search(self, query: str, top_k: int) -> list[tuple[str, float]]:
        vecs = self.embedding.embed_batch([query])
        q = vecs[0] if vecs else None
        if q is None:
            return []
        qn = math.sqrt(sum(x * x for x in q)) or 1.0
        scored: list[tuple[str, float]] = []
        for rec, v in zip(self.records, self.vectors):
            if v is None:
                continue
            dot = sum(a * b for a, b in zip(q, v))
            vn = math.sqrt(sum(x * x for x in v)) or 1.0
            scored.append((rec.rel, dot / (qn * vn)))
        scored.sort(key=lambda kv: -kv[1])
        return scored[:top_k]


def evaluate(name: str, retriever, rows: list[dict], workers: int) -> dict:
    """Run every query and aggregate recall@k, MRR, and leak-split recall@5."""
    from concurrent.futures import ThreadPoolExecutor

    top_k = max(KS)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        results = list(pool.map(lambda r: retriever.search(r["query"], top_k), rows))

    hits = {k: 0 for k in KS}
    rr_total = 0.0
    clean_hits = clean_n = 0
    per_kind: dict[str, list[int]] = {}

    for row, ranked in zip(rows, results):
        paths = [p for p, _ in ranked]
        rank = paths.index(row["target"]) + 1 if row["target"] in paths else None
        for k in KS:
            if rank and rank <= k:
                hits[k] += 1
        rr_total += 1.0 / rank if rank else 0.0

        # Low-leak queries are the honest subset: they share few words with
        # the note, so a hit cannot be explained by term copying.
        if row.get("leak", 0) <= 0.4:
            clean_n += 1
            if rank and rank <= 5:
                clean_hits += 1

        per_kind.setdefault(row.get("kind", "?"), []).append(
            1 if rank and rank <= 5 else 0
        )

    n = len(rows)
    return {
        "retriever": name,
        "n": n,
        "recall": {f"@{k}": round(hits[k] / n, 4) for k in KS},
        "mrr": round(rr_total / n, 4),
        "recall@5_low_leak": round(clean_hits / clean_n, 4) if clean_n else None,
        "low_leak_n": clean_n,
        "recall@5_by_kind": {
            kind: {"n": len(v), "recall@5": round(sum(v) / len(v), 4)}
            for kind, v in per_kind.items()
        },
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--queries", default="eval/data/queries.jsonl")
    ap.add_argument("--retriever", default="bm25",
                    help="comma-separated: semantic, bm25")
    ap.add_argument("--cache", default="eval/data/vectors.json")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--out", default="eval/data/results.json")
    args = ap.parse_args()

    qpath = Path(args.queries)
    if not qpath.exists():
        sys.exit(f"missing {qpath} — run eval/gen_queries.py first")
    rows = [json.loads(line) for line in qpath.read_text().splitlines() if line.strip()]

    base = storage.get_base_dir()
    corpus = load_corpus(base)
    print(f"corpus {len(corpus)} records, {len(rows)} queries\n")

    results = []
    for name in [s.strip() for s in args.retriever.split(",") if s.strip()]:
        print(f"[{name}]")
        if name == "bm25":
            retriever = BM25(corpus)
            workers = 1  # pure CPU, no benefit from threads
        elif name == "semantic":
            retriever = Semantic(corpus, Path(args.cache))
            workers = args.workers
        else:
            sys.exit(f"unknown retriever: {name}")

        res = evaluate(name, retriever, rows, workers)
        results.append(res)
        r = res["recall"]
        print(f"  recall@1={r['@1']:.3f}  @5={r['@5']:.3f}  @10={r['@10']:.3f}  "
              f"MRR={res['mrr']:.3f}")
        print(f"  recall@5 on low-leak queries (n={res['low_leak_n']}): "
              f"{res['recall@5_low_leak']}\n")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({
        "corpus_size": len(corpus),
        "queries": len(rows),
        "results": results,
    }, indent=2, ensure_ascii=False))
    print(f"-> {out}")


if __name__ == "__main__":
    main()
