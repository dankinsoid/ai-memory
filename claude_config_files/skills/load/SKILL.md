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

## 2. Read the blob

Read blob directory with Read/Glob:

1. **`meta.edn`** — session metadata and summary.
2. **`compact.md`** — primary source. Usually sufficient on its own.
3. **`_current.md`** — last session state. Useful if no compact or need most recent info.
4. **Named chunks** (`0001-*.md`) — only for detail beyond what compact provides.

## 3. Resume

- Summarize recovered context, state the source
- Locate relevant files, proceed with pending work
- If essential info missing — ask one focused question
