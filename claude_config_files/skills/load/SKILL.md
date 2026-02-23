---
name: load
description: Deep recovery from a previous session — read blob content and resume work. SessionStart hook provides lightweight context; this skill loads the full picture.
---

# Load Session

## 1. Find the session

SessionStart context already has "Recent Sessions" with `[blob: /path]` — check there first.

If not in context (older session, different project):

```
memory_get_facts({ filters: [{ session_id: "<session-id>" }] })
```

Extract `[blob: /path/to/dir]` from the fact.

## 2. Discover continuation chain

Run the chain discovery script with the **current** session ID (from the SessionStart hook):

```bash
bb ~/.claude/skills/load/load-chain.bb <current-session-id>
```

This does two things:
- **Traverses continuation edges** backward to find all linked previous sessions
- **Strengthens the edge** to the immediate predecessor (expressing intent to continue)

If a chain is found, read `compact.md` from each previous session blob (newest first). Earlier sessions have progressively less detail — this is by design (compression gradient).

## 3. Read the blob

Read blob directory with Read/Glob:

1. **`compact.md`** — primary source. Usually sufficient on its own.
2. **`_current.md`** — last session state. Useful if no compact or need most recent info.
3. **Named chunks** (`0001-*.md`) — only for detail beyond what compact provides.

## 4. Resume

- Summarize recovered context, state the source
- Locate relevant files, proceed with pending work
- If essential info missing — ask one focused question
