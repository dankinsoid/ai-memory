---
name: load
description: Deep recovery from a previous session — read session content and resume work.
---

# Load Session

## Resolve target session

Parse ARGUMENTS to determine which session to load:

1. **No args / "last" / "latest"** → search for the most recent session:
   Call `memory_search` with `tags: ["session", "project/<project>"]`, `limit: 1`
   This is an exact filter — load the result directly without asking.

2. **Free text** → parse the user's request into `memory_search` filters:
   - Always include `tags: ["session", "project/<project>"]`
   - Temporal hints ("yesterday", "last week") → `since`/`until` (resolve relative to today)
   - Topic keywords → `query` (translate to English)

   Call `memory_search` with the constructed filters.

## Choosing from results

If the search used `query` or topic-based tags (i.e. the filter is fuzzy/ambiguous),
**always let the user pick** using `AskUserQuestion` with up to 4 candidates as options
(label = title, description = summary). The user can pick "Other" to refine.

If the search was exact (e.g. `limit: 1` with no query), load the result directly.

## Load content

Once a session is identified, call `memory_load_session` with its `ref`.
This returns compact notes + transcript tail — optimized for recovery.

## After loading

**Do NOT announce what you loaded.** Do not say "here's what I recovered" or "what should we do next?".

Instead:
- Read the content silently
- Understand where the previous conversation left off
- Continue naturally — as if picking up mid-conversation
- If the previous session ended with a pending task or question, address it directly
- If context is insufficient to continue, ask one focused question about the specific gap
