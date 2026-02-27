---
name: save
description: Save a detailed session summary to memory before clearing context. Generates a comprehensive compact summary, stores it via memory_session MCP tool. Use before /clear or /compact, at the end of a long session, or when switching tasks. The "write" side of session continuity — /load is the "read" side.
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
- **Don't duplicate meta.edn** — project, summary, date, session-id, turn count are already there.
- **User requirements** — capture preferences, corrections, and constraints specific to this task. Skip section if there were none.

## 1.5. Harvest facts from this session

Scan the full conversation and identify 3-8 facts that weren't explicitly saved but are worth keeping — user preferences revealed, patterns discovered, pitfalls hit, what the user is focused on. For each, delegate to memory-scribe in background:

```
Task(subagent_type="memory-scribe", run_in_background=true,
     prompt="observation: <fact>\ncontext: project=<name>, session harvest")
```

Call once per fact. Skip facts already explicitly saved earlier in the session.

## 1.6. Reinforce useful facts

If facts retrieved during this session influenced task completion, call `memory_reinforce` before saving. Review facts from `memory_get_facts` calls made during the session — score only those that directly contributed to or hindered progress.

## 2. Call `memory_session`

```
memory_session({
  session_id: "<session-id>",
  summary: "<session arc as Fact Format>",
  chunk_title: "<title for last chunk>",
  compact: "<content from step 1>"
})
```

`summary` — 2-5 words, what this session was about, no articles:

```
updated load/save skills
```

## 3. Confirm to user

Show blob-dir. Tell user: "You can now /clear. To resume later: /load"
