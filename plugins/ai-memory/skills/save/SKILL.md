---
name: save
description: Save a session summary to memory. The "write" side of session continuity — /load is the "read" side.
---

# Save Session

The full transcript is saved automatically — compact captures the **substance**: what was decided, what went wrong, what the user wants. Not a play-by-play.

## 1. Generate compact content

Two parts: a **structured header** for quick orientation, then a **compression-gradient narrative** where detail increases toward the end (recent work matters most for resumption, early work only needs enough context to understand the arc).

**Skip compact for short sessions** — if the compact would be comparable in size to the sum of all messages, don't generate it. Just call `memory_session` without the `compact` field; the transcript is saved automatically and is sufficient for recovery.

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

- **Decisions over actions** — "chose X over Y because Z" is more valuable than listing what files were edited.
- **Include dead ends** — rejected approaches and *why* they were rejected, so the next agent doesn't retry them.
- **User requirements** — capture preferences, corrections, and constraints specific to this task. Skip section if there were none.

## 2. Call `memory_session` mcp tool

Pass `session_id`: `${CLAUDE_SESSION_ID}`
Pass `project`: from the SessionStart context
