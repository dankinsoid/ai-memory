---
name: save
description: Save a detailed session summary to memory before clearing context. Generates a comprehensive summary of the current session, stores it via memory_session_compact MCP tool, and prepares for context handoff.
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
Files: src/foo/bar.clj (new), src/baz.clj (modified)
Next: <immediate next action, one line>
Blockers: <if any, otherwise "none">

---

<First sentence: 1-2 sentence overview of the entire session — this gets extracted for search indexing.>

<Early session — high compression: 1-2 sentences per topic. What was done, broad strokes.>

<Middle session — moderate detail: key decisions with rationale, alternatives rejected and why.>

<Recent work — full detail: what was tried, what worked, what didn't. Include code snippets,
exact error messages, specific function names. This section gets the most space.>

<Current state: what is working, what is broken, what is half-done.>
```

#### Rules

- **Structured header always present** — Status, Files, Next, Blockers. Four lines, no exceptions.
- **Compression gradient** — early work gets 1-2 sentences, recent work gets full paragraphs. A future agent needs to know *where you left off* in detail, not *how you got started*.
- **No rigid sections in the narrative** — adapt to what actually happened. Debugging sessions look different from feature implementation, research looks different from refactoring. Allocate space proportionally to importance, not to template slots.
- **Concrete identifiers** — file paths, function names, error messages, config values. "Refactored the tag system" is useless. "Renamed `tag/tree.clj` to `tag/query.clj`, updated ns refs in `protocol.clj` and `api.clj`" is actionable.
- **Don't duplicate meta.edn** — project name, date, session-id, turn count, and the rolling 1-sentence session-summary are already in meta.edn. compact.md adds depth, not repetition.
- **Include dead ends** — rejected approaches and why they failed are valuable context. "Tried buddy.auth wrap-authorization — no effect because X" prevents the next agent from retrying.

### 2. Call MCP tool

```
memory_session_compact({
  session_id: "<current context_id from memory_remember calls>",
  compact_summary: "<the generated summary>"
})
```

### 3. Report to user

- Confirm summary was saved
- Show the blob-dir where it was stored
- Tell user: "You can now /clear. To resume later: /load"

## Important

- The summary should be **self-contained** — a future agent with no context should understand the full situation from compact.md alone
- The **first sentence after `---`** is critical — it gets extracted as the searchable summary in Datomic. Make it count: cover what the session was about and its outcome.
- The session_id comes from the `context_id` parameter used in `memory_remember` calls throughout the session
- If no `context_id` was used this session, the MCP tool will create a new session blob
