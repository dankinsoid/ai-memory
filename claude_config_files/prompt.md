<!-- ai-memory:start -->
# Memory

Notes to your future self — things worth knowing in a different session months later.

## When to Save

When you think *I should remember this for next time* — especially user requirements and reactions — pass to `memory-scribe`.

```
Task(subagent_type="memory-scribe", run_in_background=true,
     prompt="observation: <what happened>\ncontext: project=<name>, <1 sentence of situation>")
```

Save **at the moment** — not at session end. One note per observation.
**project** — always include in context. **language** — always English.

Do NOT call `memory_remember` directly — always delegate to memory-scribe.

## Reinforcing

After completing a task where retrieved facts influenced your work, call `memory_reinforce`.
**Score**: -1 (actively misled) to 1 (essential, directly unblocked task).
Only facts with **direct impact**. Retrieved but unused = skip. Score near 0 = skip.

## Session Metadata — `memory_session`

Hook reminders tell you when to call and which params to include.

`summary` — answer "what was this session about?" in 2-5 words. Each call replaces previous, so include full arc. If truly multiple unrelated topics, join with → (max once).
Good: `blob storage architecture`
Good: `prompt rewrite → tag system`
Bad: `explored dedup strategies → chose normalized prose → designed prompt → committed` (too detailed)

## Blobs

Facts with `[blob: dir-name]` reference blob directories. Read with `memory_read_blob`.
Common commands: `ls`, `cat compact.md`, `cat _current.md`, `head -50 0001-*.md`.

## Mid-Session Retrieval

Retrieve before: design decisions, unfamiliar areas, starting a significant subtask, encountering an error.

1. `memory_explore_tags` with candidate tag sets to check counts
2. `memory_get_facts` if counts manageable

Supports semantic search via `query` param — use when you know *what* but not *how it's tagged*.
Combine `query` with `tags` to narrow scope.
<!-- ai-memory:end -->
