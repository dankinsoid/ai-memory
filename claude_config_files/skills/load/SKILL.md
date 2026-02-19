---
name: load
description: Recover context from a previous session using ai-memory blobs and resume where we left off. Falls back to ~/.claude/todos if no memory sessions found.
---

# Load Session

## Goal

Recover context from a previous session and continue where we left off.

## Workflow

### Step 1: Check memory for saved sessions (preferred)

Use ai-memory MCP tools to find the most recent session with context:

1. `memory_list_blobs({ limit: 5 })` — list recent blobs, look for session blobs
2. Pick the most recent session blob for the current project
3. `memory_read_blob({ blob_dir: "..." })` — read metadata overview
   - If it has a **compact summary** — this is the richest context, use it as primary
   - If it has a **session summary** — use as overview
4. If more detail needed, read specific sections:
   - Last 2-3 sections for recent context: `memory_read_blob({ blob_dir: "...", section: N })`

If a compact summary is found, it should contain everything needed to resume:
accomplished tasks, key decisions, current state, pending work, and recent interactions.

### Step 2: Fall back to todos (if no memory sessions)

If memory has no relevant sessions:

1. Identify the target todo file.
   - Default: use the newest file by modification time in `~/.claude/todos`.
   - If the user specifies a file or identifier, use that instead.
   - If the newest file is empty (`[]`), scan the next newest until a non-empty file is found.

2. Read and summarize the todos.
   - Extract `in_progress` items first, then `pending` items.
   - Preserve the original Russian wording for task titles when present.
   - If there is an `activeForm`, prefer it for describing the current action.

### Step 3: Continue execution

- Summarize recovered context to the user
- Locate the relevant files in the repo
- Proceed with the in-progress/pending task
- If essential info is missing, ask a single focused question

## Output expectations

- State where context was recovered from (memory blob or todo file)
- Summarize what was happening and what's next
- Keep it concise and action-oriented — then start working
