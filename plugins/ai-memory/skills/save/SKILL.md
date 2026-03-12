---
name: save
description: Save a detailed session summary to memory. The "write" side of session continuity — /load is the "read" side.
---

# Save Session

The compact content must be **self-contained** — a future agent with zero context should understand the full situation from it alone.

## 1. Generate compact content

Two parts: a **structured header** for quick orientation, then a **compression-gradient narrative** where detail increases toward the end (recent work matters most for resumption, early work only needs enough context to understand the arc).

```markdown
Status: in-progress | completed | blocked
Next: <immediate next action, one line>
Blockers: <if any, otherwise "none">

---

<Overview sentence — what was this session about, what's the outcome.>

<Early session — high compression: 1-2 sentences per topic.>

<Middle session — moderate detail: key decisions with rationale.>

<Recent work — full detail: what was tried, what worked, what didn't.
Include code snippets, exact error messages, specific function names.>

<Current state: what is working, what is broken, what is half-done.>

## User requirements
- <requirements, preferences, corrections from the user>
- <specific to this project/task — things the next agent must respect>
```

### Rules

- **Concrete identifiers** — file paths, function names, error messages. Not "refactored the tag system" but "renamed `tag/tree.clj` → `tag/query.clj`".
- **Include dead ends** — rejected approaches and *why* they were rejected, so the next agent doesn't retry them.
- **User requirements** — capture preferences, corrections, and constraints specific to this task. Skip section if there were none.

## 2. Call `memory_session` mcp tool

Pass `session_id`: `${CLAUDE_SESSION_ID}`
Pass `project`: from the SessionStart context

## 3. Confirm to user

Show the file path returned by memory_session. Tell user: "You can now /clear. To resume later: /load"
