# External Memory (ai-memory)

Read before unfamiliar tasks; write when you learn something worth keeping.

## Before Starting a Task

Query memory for rules relevant to the task topic:
```json
{"tags": ["<task-topic>"], "any_tags": ["rule", "conventions"]}
```
Example: refactoring Go code → `tags: ["go"]`, debugging Flutter → `tags: ["debugging", "flutter-cljd"]`.

## Free Retrieval

You can query memory freely anytime you need any user or project-specific info - it's your external brain.

## Failure Handling

If any memory operation fails (memory-scribe, MCP tools, "Memory Unavailable" in hook output) — tell the user in one line, don't retry, continue your task.

## Session Metadata

Hook reminders tell you when to call `memory_session` and which params to include.

- `title` — 2-5 words, English
- `summary` — 1-2 sentences: problem, approach, key decisions. Each call replaces previous, so include full arc. No file/function names.
- `tags` — topic tags (e.g. `["refactoring", "architecture"]`)
