# External Memory (ai-memory)

Read before unfamiliar tasks; write when you learn something worth keeping.

## When to Query Memory

**Always** call `memory_search` before proceeding when you:
- Start work on a new topic or technology
- Are about to make any decision not explicitly stated — approach, naming, style, structure, tooling
- Face any design or architectural choice
- Feel uncertain about conventions or best practices

Do not fill in gaps with defaults or assumptions — check memory first.

Search by topic: `memory_search(any_tags=["<topic>", "<language>"])`

## Free Retrieval

You can query memory freely anytime you need user or project-specific info — it's your external brain.

## Failure Handling

If any memory operation fails (memory-scribe, MCP tools, "Memory Unavailable" in hook output) — tell the user in one line, don't retry, continue your task.

## Session Metadata

Hook reminders tell you when to call `memory_session` and which params to include.

- `title` — 2-5 words, English
- `summary` — 1-2 sentences: problem, approach, key decisions. Each call replaces previous, so include full arc. No file/function names.
- `tags` — topic tags (e.g. `["refactoring", "architecture"]`)
