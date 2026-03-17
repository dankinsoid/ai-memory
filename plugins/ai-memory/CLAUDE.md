# External Memory (ai-memory)

## Rules in Memory

Rules stored in memory are **primary conventions** — equal in authority to CLAUDE.md instructions. They capture the user's preferences, project conventions, and coding standards that evolve over time.

Call `memory_search(tags=["rule"])` when you're about to make a choice that conventions could govern — architecture, naming, style, tooling, testing approach, error handling patterns. If you're unsure whether a convention exists, search. The cost of a redundant search is near zero; the cost of violating an established convention is high.

Rules may also be loaded automatically via hooks — you'll see them in system-reminder messages. Follow them as you would CLAUDE.md instructions.

## Language

All memory writes must be in **English** — titles, summaries, content, tags, compact notes. Translate from user's language if needed.

## Failure Handling

If any memory operation fails (MCP tools, "Memory Unavailable" in hook output) — tell the user in one line, don't retry, continue your task.
