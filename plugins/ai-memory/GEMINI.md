# External Memory (ai-memory)

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

## Language

All memory writes must be in **English** — titles, summaries, content, tags, compact notes. Translate from user's language if needed.

## Failure Handling

If any memory operation fails (MCP tools, "Memory Unavailable" in hook output) — tell the user in one line, don't retry, continue your task.
