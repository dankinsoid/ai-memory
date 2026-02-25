<!-- ai-memory:start -->
# Memory

You have persistent memory across sessions and projects. Read it before unfamiliar tasks; write to it when you learn something worth keeping.

## When to Save

Pass to `memory-scribe` when any of these rings true:

*I wish I'd known this earlier.*
*I should remember this for next time.*
*My understanding of something just changed.*
*I'd want to know this before a similar task.*
*This is interesting — worth keeping.*

Especially: user requirements and reactions.

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

`summary` — answer "what was this session about?" in 2-5 words. Each call replaces previous, so include full arc.
Good: `blob storage architecture`
Good: `prompt rewrite and tag system`
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
