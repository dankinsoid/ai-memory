# External Memory (ai-memory)

Read before unfamiliar tasks; write when you learn something worth keeping.

## When to Save

Pass to `memory-scribe` when you observe: **events** (implementations, bug fixes, reverts, failed approaches), **decisions** (tech choices, trade-offs), **lessons** (pitfalls, surprises, non-obvious constraints), **preferences** (style, workflow, "always/never" rules).

```
Task(subagent_type="memory-scribe", run_in_background=true,
     prompt="observation: <what happened>\ncontext: project=<name>, <1 sentence of situation>")
```

Save **at the moment** — not at session end. One note per observation. Always include project. English only.
Do NOT call `memory_remember` directly — always delegate to memory-scribe.

## Reinforcing

After completing a task where retrieved facts influenced your work, call `memory_reinforce`.
Score: -1 (misled) to 1 (essential). Only facts with **direct impact**. Unused or near-zero = skip.

## Session Metadata — `memory_session`

Hook reminders tell you when to call and which params to include.

- `title` — 2-5 words, English. e.g. `blob storage architecture`, `fix auth bug`
- `summary` — 1-2 sentences: problem, approach, key decisions. Each call replaces previous, so include full arc. No file/function names — those belong in compact blob.
  - Good: `Designed blob storage using Node model with filesystem sections. Chose lazy navigation over pre-indexed TOC.`
  - Bad: `blob storage architecture` (too short — use title for this)
  - Bad: `Replaced :node/tag-refs with :node/tags across 17 files.` (implementation details, no essence)
- `tags` — topic tags (e.g. `["refactoring", "architecture"]`). Merged with automatic `session` tag.

## Project Summaries — `memory_project`

Call when project architecture, tech stack, or goals become clear or change (first session, key decisions, scope shifts). One fact per project, upserted. 3-8 sentences: what, stack, state, constraints.

## Blobs

Facts with `[blob: dir-name]` reference blob directories. `memory_read_blob` runs bash in a blob dir.

## Mid-Session Retrieval

Retrieve before: design decisions, unfamiliar areas, significant subtasks, errors.

1. `memory_explore_tags` — check tag counts
2. `memory_get_facts` — fetch if counts manageable

Semantic search via `query` param — use when you know *what* but not *how it's tagged*. English queries. Combine `query` with `tags` to narrow scope.
