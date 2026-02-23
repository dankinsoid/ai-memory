---
name: load
description: Deep recovery from a previous session — read blob content and resume work. SessionStart hook provides lightweight context; this skill loads the full picture.
---

# Load Session

## Resolve target session

Parse ARGUMENTS to determine which session to load:

1. **Blob dir** (matches `*_session-*`) → use directly
2. **No args** → traverse continuation chain from current session:
   ```bash
   bb ~/.claude/skills/load/load-chain.bb <current-session-id>
   ```
   The current session ID is in SessionStart context. The script picks up prev-session cache files (written by SessionEnd on /clear), creates continuation edges, then traverses the chain.
3. **Free text** (e.g. "сессию где чинили save", "последнюю") → semantic search:
   ```
   memory_get_facts with {query: "<user text>", tags: ["session"], limit: 5, sort_by: "date"}
   ```
   Pick best match, extract its `[blob: dir-name]`.

## Load content

For specific blob:
```bash
bb ~/.claude/skills/load/load-chain.bb --blob <blob-dir>
```

For continuation chain (no args):
```bash
bb ~/.claude/skills/load/load-chain.bb <current-session-id>
```

Script outputs: compact summary + last conversation turns.

## After loading

**Do NOT announce what you loaded.** Do not say "here's what I recovered" or "what should we do next?".

Instead:
- Read the output silently
- Understand where the previous conversation left off
- Continue naturally — as if picking up mid-conversation
- If the previous session ended with a pending task or question, address it directly
- If context is insufficient to continue, ask one focused question about the specific gap
