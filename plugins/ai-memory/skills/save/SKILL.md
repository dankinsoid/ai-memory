---
name: save
description: Save a session summary to memory. The "write" side of session continuity — /load is the "read" side.
---

# Save Session

The full transcript is saved automatically — compact captures the **substance**: what was decided, what went wrong, what the user wants. Not a play-by-play.

## 1. Generate compact content

```markdown
Status: in-progress | completed | blocked
Next: <immediate next action, one line>
Blockers: <if any, otherwise "none">

<Core of the session — what problem was solved, what was built/changed. 2-3 sentences.>

<Key decisions — what was chosen, what was rejected and why.>

<Mistakes & dead ends — what didn't work and why, so the next agent avoids them.>

<User requirements — preferences, corrections, constraints from the user.>

<Current state — what works, what's broken, what's half-done.>
```

### Rules

- **Decisions over actions** — "chose X over Y because Z" is more valuable than listing what files were edited.
- **No file listings** — the transcript and git log have those.
- **Include dead ends** — rejected approaches with rationale so the next agent doesn't retry.
- **User requirements** — always capture; these are the most important part for continuity.

## 2. Call `memory_session` mcp tool

Pass `session_id`: `${CLAUDE_SESSION_ID}`
Pass `project`: from the SessionStart context

## 3. Confirm to user

Show the file path returned by memory_session. Tell user: "You can now /clear. To resume later: /load"
