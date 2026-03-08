---
name: load
description: Deep recovery from a previous session — read blob content and resume work. SessionStart hook provides lightweight context; this skill loads the full picture.
---

# Load Session

## Resolve target session

Parse ARGUMENTS to determine which session to load:

1. **No args** → traverse continuation chain from current session:
   ```bash
   bb <this-skill-dir>/load-chain.bb <current-session-id> <project-name>
   ```
   `<this-skill-dir>` = the directory containing this SKILL.md (derive from the path you loaded it from).
   The current session ID is in SessionStart context. Determine the project name from the git repo name. The script picks up prev-session cache files for that project only, creates continuation edges, then traverses the chain.
2. **Free text** → translate the user's request into `memory_get_facts` filter params.
   Always include `tags: ["session"]` (+ project tag if known). Use `query` only when the user describes session content — for recency or time ranges, structured params suffice.
   Always write `query` in English regardless of what language the user used.
   Pick best match from results (skip current session), extract its `[blob: dir-name]`.
3. **Blob dir** (matches `*_session-*`) → use directly

## Load content

For continuation chain (no args), run the script — it traverses chain edges and outputs combined context:
```bash
bb <this-skill-dir>/load-chain.bb <current-session-id> <project-name>
```

For a specific blob dir, use `memory_read_blob` to read its contents.

## After loading

**Do NOT announce what you loaded.** Do not say "here's what I recovered" or "what should we do next?".

Instead:
- Read the output silently
- Understand where the previous conversation left off
- Continue naturally — as if picking up mid-conversation
- If the previous session ended with a pending task or question, address it directly
- If context is insufficient to continue, ask one focused question about the specific gap
