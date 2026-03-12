---
name: load
description: Deep recovery from a previous session — read session content and resume work. SessionStart hook provides lightweight context; this skill loads the full picture.
---

# Load Session

## Resolve target session

Parse ARGUMENTS to determine which session to load:

1. **No args** → find previous session via prev-session cache:
   ```bash
   python3 <this-skill-dir>/load-chain.py <current-session-id> <project-name>
   ```
   `<this-skill-dir>` = the directory containing this SKILL.md (derive from the path you loaded it from).
   The current session ID is in SessionStart context. Determine the project name from the git repo name.
   The script finds the previous session via the prev-session cache (written by session-end hook on /clear).

2. **Free text** → parse the user's request into `memory_search` filters:
   - Always include `tags: ["session", "project/<project>"]`
   - Temporal hints ("last", "yesterday", "last week") → `since`/`until` (resolve relative to today)
   - Topic keywords → `query` (translate to English)
   - "latest" / "last" without topic → omit `query`, use `limit: 1`

   Call `memory_search` with the constructed filters, pick the best match, load via `--ref`:
   ```bash
   python3 <this-skill-dir>/load-chain.py --ref <ref field from result, e.g. [[some-session-stem]]>
   ```

3. **File path** (matches `sessions/...`) → use directly:
   ```bash
   python3 <this-skill-dir>/load-chain.py --file <rel-path>
   ```

## Load content

Run the script — it outputs session content with priority: Compact section (from /save),
then messages.md (from Stop hook), then summary as fallback.

## Handling CHOOSE_SESSION

If the script output starts with `# CHOOSE_SESSION`, no session was found for the current ID.
The output contains a numbered list of recent session candidates.

**You MUST ask the user which session to load** using a question with the candidate list.
Present the candidates clearly (number, title, summary). Let the user pick by number or description.

Once the user picks, load that session's file via:
```bash
python3 <this-skill-dir>/load-chain.py --file <file-path from [file: ...] marker>
```

## After loading

**Do NOT announce what you loaded.** Do not say "here's what I recovered" or "what should we do next?".

Instead:
- Read the output silently
- Understand where the previous conversation left off
- Continue naturally — as if picking up mid-conversation
- If the previous session ended with a pending task or question, address it directly
- If context is insufficient to continue, ask one focused question about the specific gap
