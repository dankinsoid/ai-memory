---
name: save
description: Save a session summary to memory. The "write" side of session continuity — /load is the "read" side.
---

# Save Session

The full transcript is saved automatically — compact captures the **substance**: what was decided, what went wrong, what the user wants. Not a play-by-play.

Compact is a **handoff to a future agent** — write what you'd need to continue this work with no other context. Be concrete and actionable, not polished.

## 1. Generate compact content

Two parts: a **structured header** for quick orientation, then a **compression-gradient narrative** where detail increases toward the end (recent work matters most for resumption, early work only needs enough context to understand the arc).

**Skip compact for short sessions** — if the compact would be comparable in size to the sum of all messages, don't generate it. Just call `memory_session` without the `compact` field; the transcript is saved automatically and is sufficient for recovery.

```markdown
Goal: <why this session exists — intent, not just topic>
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

## Dead ends
- <approach tried → why it failed> (skip section if none)

## User requirements
- <requirements, preferences, corrections from the user>
- <specific to this project/task — things the next agent must respect>
```

### Rules

- **Substance, not mechanics** — "chose X over Y because Z" matters; which files were edited does not. Focus on intent, reasoning, and constraints — things that only exist in the conversation.
- **Dead ends** — rejected approaches and *why* they were rejected, so the next agent doesn't retry them. Promote to a dedicated section.
- **User requirements — preserve exact wording** — paraphrase loses intent. Use direct quotes (translated to English if needed): "User: 'don't mock the database'" is better than "user prefers real DB in tests". Only quote requirements relevant to the current task — skip stale topics from earlier in the conversation that were superseded or abandoned.
- **Current task in focus, history through its lens** — most detail goes to what we're working on now. Earlier parts of the conversation are kept only to the extent they inform the current task: decisions still in effect, constraints discovered, context that explains *why* we're doing what we're doing. Drop the chronological journey — keep the causal chain.
- **Length: keep total compact under 2000 characters** — compress aggressively if needed. The `/load` budget is shared with facts and transcript tail; a bloated compact crowds them out.

## 2. Call `memory_session` mcp tool

Pass `session_id`: `${CLAUDE_SESSION_ID}`
Pass `project`: from the SessionStart context
