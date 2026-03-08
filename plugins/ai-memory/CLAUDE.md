# External Memory (ai-memory)

Read before unfamiliar tasks; write when you learn something worth keeping.

## Before Starting a Task

Query memory for rules relevant to the task topic. Use `any_tags` for rule types, `tags` for the topic:
```json
{"tags": ["<task-topic>"], "any_tags": ["rule", "critical-rule", "preference", "conventions"]}
```
Example: refactoring Go code → `tags: ["go"]`, debugging Flutter → `tags: ["debugging", "flutter-cljd"]`.

## Free Retrieval

You can query memory freely anytime you need any user or project-specific info - it's your external brain.

## When to Save

Pass to `memory-scribe` when you observe: **events** (implementations, bug fixes, reverts, failed approaches), **decisions** (tech choices, trade-offs), **lessons** (pitfalls, surprises, non-obvious constraints), **preferences** (style, workflow, "always/never" rules).

```
Task(subagent_type="memory-scribe", run_in_background=true,
     prompt="observation: <what happened>\ncontext: project=<name>, <1 sentence of situation>")
```

Save **at the moment** — not at session end. One note per observation. Always include project. English only.
Do NOT call `memory_remember` directly — always delegate to memory-scribe.

## Reinforcing

After completing a task where retrieved facts influenced your work, call `memory_reinforce`.
Score: -1 (misled) to 1 (essential). Only facts with **direct impact**. Unused or near-zero = skip.

## Session Metadata

Hook reminders tell you when to call `memory_session` and which params to include.

- `title` — 2-5 words, English
- `summary` — 1-2 sentences: problem, approach, key decisions. Each call replaces previous, so include full arc. No file/function names.
- `tags` — topic tags (e.g. `["refactoring", "architecture"]`)
