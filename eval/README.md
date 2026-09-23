# Retrieval evaluation

Measures how reliably memory search finds the right note, on the real vault.

## Method

1. `corpus.py` collects every record `storage.reindex()` would embed, using the
   same functions — the benchmark indexes exactly what production indexes.
2. `gen_queries.py` samples notes and asks an LLM to write queries a developer
   would type from faded memory. The source note is the ground truth. The prompt
   forbids reusing the note's distinctive wording; a per-query `leak` score
   records how many content words still overlap, so the honest low-leak subset
   can be reported separately.
3. `score.py` ranks the whole corpus per query and reports recall@k and MRR for
   the production semantic path and for a BM25 lexical floor.

## Run

```bash
export OPENAI_API_KEY=sk-...
export AI_MEMORY_DIR="$HOME/Documents/Obsidian Vault/ai-memory"
export AI_MEMORY_EMBEDDING=true

python3 eval/gen_queries.py --n 200 --per-doc 2
python3 eval/score.py --retriever semantic,bm25
```

Corpus vectors are cached in `eval/data/vectors.json`, so re-scoring only
embeds the queries.

## Findings

**Hybrid retrieval does not help.** RRF fusion of BM25 into the semantic ranking
was measured across weights 0.0-1.0; every non-zero weight lowered recall@5 on
low-leak queries, monotonically (0.75 at w=0, 0.71 at w=0.1, 0.58 at w=1.0). The
apparent closeness of BM25 to semantic in the headline numbers is a leak
artifact — on paraphrased queries BM25 scores 0.38 against semantic's 0.75, so
fusion contributes noise rather than the exact-term matching it was expected to
add.

## Caveats

- Ground truth is LLM-generated, so it measures "can search find the note this
  query was written from", not real user satisfaction.
- The vault is ~99% session summaries; the numbers say little about fact/rule
  retrieval until more facts exist.
- `leak` bounds, but does not eliminate, lexical give-away. Trust
  `recall@5_low_leak` over the headline number.
