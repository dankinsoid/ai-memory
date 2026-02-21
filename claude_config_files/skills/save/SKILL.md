---
name: save
description: Save a detailed session summary to memory before clearing context. Generates a comprehensive summary of the current session, stores it via memory_session MCP tool, and prepares for context handoff.
---

# Save Session

## Goal

Generate and store a session summary so that a future agent with zero context can resume this work. This is the "write" side of session continuity — `/load` is the "read" side.

## When to use

- Before `/clear` or `/compact` when context is getting large
- At the end of a long session
- When switching to a different task/project

## Workflow

### 1. Generate compact.md

Two parts: a **structured header** for quick orientation, then a **compression-gradient narrative** where detail increases toward the end.

```markdown
Status: in-progress | completed | blocked
Next: <immediate next action, one line>
Blockers: <if any, otherwise "none">

---

<Overview sentence — what was this session about and what's the outcome.>

<Early session — high compression: 1-2 sentences per topic.>

<Middle session — moderate detail: key decisions with rationale.>

<Recent work — full detail: what was tried, what worked, what didn't.
Include code snippets, exact error messages, specific function names.>

<Current state: what is working, what is broken, what is half-done.>

## User requirements
- <requirements, preferences, corrections, and remarks from the user>
- <specific to this project/task — things the next agent must respect>
```

#### Rules

- **Structured header always present** — Status, Next, Blockers.
- **Compression gradient** — early work gets 1-2 sentences, recent work gets full paragraphs.
- **Concrete identifiers** — file paths, function names, error messages. Not "refactored the tag system" but "renamed `tag/tree.clj` to `tag/query.clj`".
- **Don't duplicate meta.edn** — project, summary, date, session-id, turn count are already there.
- **Include dead ends** — rejected approaches prevent the next agent from retrying.
- **User requirements section** — capture user requirements, preferences, corrections, and remarks specific to this project/task. Skip if there were none.

### 2. Call `memory_session` with all three params

- `summary` — session arc as a fact (follows Fact Format: lowercase, no articles, imperative/declarative)
- `chunk_title` — title for the last chunk of work
- `compact` — the generated content from step 1

### 3. Report to user

- Confirm summary was saved, show blob-dir
- Tell user: "You can now /clear. To resume later: /load"

## Important

- The compact content should be **self-contained** — a future agent with no context should understand the full situation from it alone.
