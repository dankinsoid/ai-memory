<!-- ai-memory:start -->
# External Memory (ai-memory)

This is a supplemental long-term memory layer beyond Claude's built-in memory. It provides cross-project fact storage, semantic search, session blobs, and session syncing via an external MCP server.

Read it before unfamiliar tasks; write to it when you learn something worth keeping.

## When to Save

Pass to `memory-scribe` when any of these occur:

**Events** — concrete things that happened:
- Implemented, added, removed, refactored something notable
- Fixed a bug (what broke, what fixed it)
- Reverted something (what and why)
- Tried an approach that didn't work

**Decisions** — choices made with rationale:
- Tech choices, architecture decisions, design patterns chosen
- Trade-offs accepted

**Lessons** — things learned:
- Pitfalls, surprising behaviors, non-obvious constraints
- Things that contradicted expectations

**Preferences** — user or project preferences:
- Code style, workflow, tooling choices
- "Always do X", "never do Y"

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

## Project Summaries — `memory_project`

Call when the project's architecture, tech stack, goals, or structure become clear or change meaningfully:
- First substantial session on a new project
- After key architectural decisions (tech choice, data model, design patterns)
- When project scope or goals shift noticeably

`memory_project(project="my-project", summary="...")`

One fact per project, upserted in-place. Keep summary concise (3-8 sentences): what the project is, its tech stack, current state, key constraints.

## Blobs

Facts with `[blob: dir-name]` reference blob directories. `memory_read_blob` runs bash in a blob directory — treat it like any directory on the filesystem.

## Mid-Session Retrieval

Retrieve before: design decisions, unfamiliar areas, starting a significant subtask, encountering an error.

1. `memory_explore_tags` with candidate tag sets to check counts
2. `memory_get_facts` if counts manageable

Supports semantic search via `query` param — use when you know *what* but not *how it's tagged*.
Combine `query` with `tags` to narrow scope.
<!-- ai-memory:end -->
