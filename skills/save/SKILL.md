---
name: save
description: Save a detailed session summary to memory before clearing context. Generates a comprehensive summary of the current session, stores it via memory_session_compact MCP tool, and prepares for context handoff.
---

# Save Session

## Goal

Generate and store a comprehensive session summary so that a future session (or after /clear) can resume with full context. This is the "write" side of session continuity — `/load-session` is the "read" side.

## When to use

- Before `/clear` or `/compact` when context is getting large
- At the end of a long session
- When switching to a different task/project

## Workflow

1. **Generate summary** covering these sections:

```
## Accomplished
- What was done this session (completed tasks, features, fixes)

## Key decisions
- Important choices made and their rationale

## Current state
- Where things stand right now (what's working, what's built)

## Unfinished / Next steps
- Pending tasks, known issues, what to do next

## Recent context
- The last 2-3 interactions in detail (most important for resuming)
```

2. **Call MCP tool:**

```
memory_session_compact({
  session_id: "<current context_id from memory_remember calls>",
  compact_summary: "<the generated summary>"
})
```

3. **Report to user:**
   - Confirm summary was saved
   - Show the blob-dir where it was stored
   - Tell user: "You can now /clear. To resume later: /load-session"

## Important

- The summary should be **self-contained** — a future agent with no context should understand the full situation
- Emphasize the **end of the session** — recent decisions and state matter most
- Include specific file names, function names, error messages — concrete details help resumption
- The session_id comes from the `context_id` parameter used in `memory_remember` calls throughout the session
