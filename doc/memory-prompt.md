# Memory System Instructions

Source of truth for ai-memory integration prompts. Copy to `~/.claude/CLAUDE.md` for agent use.

---

# Memory

Long-term memory across sessions and projects. 9 MCP tools via `ai-memory` server.

## Session Start

Before working on the first message:

1. `memory_browse_tags({ path: null, depth: 4 })` — see what knowledge exists
2. `memory_get_facts({ tag_sets: [["pref"], ["proj/{project}"]] })` — load preferences + project context
3. Based on the task, load relevant domain facts too

Multiple tag sets in one call. Use `memory_count_facts` first only when a tag set might return 50+ results.

## After Each Message

Call `memory_remember` silently with turn summary and any extracted facts:

```
memory_remember({
  context_id: "<session-id>",
  turn_summary: "User: <request gist> → <what I did/decided>",
  session_summary: "One-sentence rolling summary of entire session so far",
  nodes: [
    { content: "Prefers X over Y", tags: ["pref/...", "lang/..."], node_type: "preference" }
  ]
})
```

**turn_summary** — always include. One line: what the user asked, what you did. Server stores it in a RAM buffer and matches to blob sections by timestamp (not a fact).

**session_summary** — always include. Rolling 1-sentence summary of the entire session so far. Stored as a searchable Datomic fact with `proj/*` tag. Updated each turn (upsert).

**nodes** — only when something worth remembering was learned:

- **Preference** — how the user likes things done
- **Decision** — choice + rationale
- **Error pattern** — non-obvious problem + solution
- **Domain fact** — project/API/codebase knowledge
- **Meta-pattern** — broader theme across observations

Not every turn produces facts. `nodes` can be empty — turn summary alone is valuable.

### Fact Quality

Good: self-contained, specific, has rationale, 2-4 tags from different branches.
Bad: restates code/docs, too vague, temporary, trivially obvious.

### Abstraction Levels

- **Concrete**: specific technical fact
- **Pattern**: recurring approach or preference
- **Meta**: underlying philosophy or principle

When 3+ concrete facts share a theme — synthesize a meta-fact.

## Mid-Session Retrieval

When facing a design decision or unfamiliar area:

1. `memory_count_facts` with candidate tag sets
2. `memory_get_facts` if counts manageable

## Tags

- Short kebab-case: `lang/clj`, `proj/ai-memory`, `pref/coding-style`, `pattern/error-handling`
- Browse taxonomy before creating — prefer existing tags
- Create freely when nothing fits, keep flat (avoid >3 nesting levels)
- Root prefixes: `lang`, `pattern`, `proj`, `pref`
