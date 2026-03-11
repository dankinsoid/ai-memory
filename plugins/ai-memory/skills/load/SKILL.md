---
name: load
description: Deep recovery from a previous session — read session content and resume work. SessionStart hook provides lightweight context; this skill loads the full picture.
---

# Load Session

## Resolve target session

Parse ARGUMENTS to determine which session to load:

1. **No args** → traverse continuation chain from current session:
   ```bash
   python3 <this-skill-dir>/load-chain.py <current-session-id> <project-name>
   ```
   `<this-skill-dir>` = the directory containing this SKILL.md (derive from the path you loaded it from).
   The current session ID is in SessionStart context. Determine the project name from the git repo name.
   The script finds the previous session by ID, traverses `continues:` wiki-links, then outputs combined context.

2. **Free text** → translate the user's request to English and pass as the first positional arg... no: instead call `memory_search` with `tags: ["session", "project/<project>"]` and `text: "<english query>"`, pick the best match, then load via `--file`:
   ```bash
   python3 <this-skill-dir>/load-chain.py --file <path from result>
   ```

3. **File path** (matches `sessions/...`) → use directly:
   ```bash
   python3 <this-skill-dir>/load-chain.py --file <rel-path>
   ```

## Load content

Run the script — it reads `messages.md` (compact content) for the most recent session,
and shows just title + summary for older sessions in the chain.

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
