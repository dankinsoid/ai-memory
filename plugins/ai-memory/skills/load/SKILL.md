---
name: load
description: Deep recovery from a previous session — read session content and resume work.
---

# Load Session

## Resolve target session

Parse ARGUMENTS to determine which session to load:

1. **No args** → present the recent sessions from SessionStart context for the user to choose.
   Show 3-4 sessions as options (label = title, description = summary + date).
   If `AskUserQuestion` tool is available, use it with structured options.
   Otherwise, present a numbered list and ask the user to pick.
   Include an "Other / search" option so the user can type a query if none match.
   If "Other" is picked, ask for a search query and proceed to step 3.

2. **"last" / "latest"** → load the most recent session directly.
   Call `memory_search` with `tags: ["session", "project/<project>"]`, `limit: 1`,
   `exclude_session_id: <current session_id>`. Load the result directly.

3. **Free text** → first scan the sessions already in SessionStart context.
   If a session's title or summary clearly matches the user's request, use its `[[ref]]` directly —
   no need to call `memory_search`.

   If no context match, fall back to `memory_search`:
   - Always include `tags: ["session", "project/<project>"]`, `exclude_session_id: <current session_id>`
   - Temporal hints ("yesterday", "last week") → `since`/`until` (resolve relative to today)
   - Topic keywords → `query` (translate to English)

## Choosing from results

If `memory_search` was used with `query` or topic-based tags (fuzzy/ambiguous),
**always let the user pick** from up to 4 candidates (label = title, description = summary).
Use `AskUserQuestion` with structured options if available, otherwise a numbered list.
The user can pick "Other" to refine.

If the match was exact (from context or `limit: 1` with no query), load directly.

## Load content

Once a session is identified, call `memory_load_session` with its `ref` (the `[[ref]]` wikilink stem).
This returns compact notes + transcript tail — optimized for recovery.

## After loading

**Do NOT announce what you loaded.** Do not say "here's what I recovered" or "what should we do next?".

Instead:
- Read the content silently
- Understand where the previous conversation left off
- Continue naturally — as if picking up mid-conversation
- If the previous session ended with a pending task or question, address it directly
- If context is insufficient to continue, ask one focused question about the specific gap
