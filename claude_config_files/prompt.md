<!-- ai-memory:start -->
# Memory

Long-term memory across sessions and projects. 5 MCP tools via `ai-memory` server.

## Session Start

Before working on the first message:

1. `memory_explore_tags({ limit: 50 })` — see all tags sorted by usage
2. `memory_get_facts({ filters: [{ tags: ["pref"] }, { tags: ["universal"] }, { tags: ["{project}"] }] })` — load preferences, universal facts, and project context
3. Based on the task, load relevant domain facts too (e.g. `{ tags: ["clojure", "error-handling"] }` — intersection of two tags)

Multiple filters in one call. Use `memory_explore_tags({ tag_sets: [...] })` first only when a tag set might return 50+ results.

## Remembering

Call `memory_remember` when the turn produced something worth persisting. **Skip when nothing new was learned** (greetings, confirmations, searches with no conclusions).

```
memory_remember({
  session_id: "<session-id>",
  project: "<project-name>",
  nodes: [
    { content: "Prefers X over Y", tags: ["pref", "coding-style"] }
  ]
})
```

**project** — always include.
**nodes** — only for durable knowledge: preferences, decisions, error patterns, domain facts, meta-patterns.

### Fact Quality

Good: self-contained, specific, has rationale, 2-4 tags.
Bad: restates code/docs, too vague, temporary, trivially obvious.

### Abstraction Levels

- **Concrete**: specific technical fact
- **Pattern**: recurring approach or preference
- **Meta**: underlying philosophy or principle

When 3+ concrete facts share a theme — synthesize a meta-fact.

## Session Metadata — `memory_session`

Hook reminders will tell you when to call and which params to include. All params except `session_id` are optional.

## Blobs (detailed content)

All blob facts are auto-tagged `blob`. Find them via `memory_get_facts({ filters: [{ tags: ["blob"] }] })`.

Facts with `[blob: /path/to/dir]` link to detailed content on disk. Use Read/Glob/Grep on the blob directory directly:

- Start with `compact.md` or `meta.edn` for overview
- Named chunks (`01-designed-auth.md`, `02-fixed-tests.md`) are the main content
- `_current.md` is the latest unnamed chunk
- Never read large files whole — use Grep or Read with offset/limit

## Mid-Session Retrieval

When facing a design decision or unfamiliar area:

1. `memory_explore_tags` with candidate tag sets
2. `memory_get_facts` if counts manageable

Filters support semantic search via `query` — use when you know *what* you're looking for but not *how it's tagged*:

`memory_get_facts({ filters: [{ query: "error handling in async pipelines", limit: 10 }] })`

Combine with tags to scope: `{ tags: ["clj"], query: "async", limit: 10 }`

## Tags

- Atomic kebab-case strings: `clojure`, `ai-memory`, `pattern`, `coding-style`, `error-handling`
- One node → many tags (flat set, no hierarchy)
- Query by intersection: `["ai-memory", "clojure"]` = facts tagged with BOTH
- `universal` — tag for facts relevant in every session regardless of project (e.g. cross-cutting patterns, tooling, meta-preferences)
- Browse tags before creating — prefer existing
- It's allowed to use more detailed tags in order to avoid ambiguity (e.g. `project/claude-memory` instead of just `claude-memory`) but always prefer existing tags if they fit.
<!-- ai-memory:end -->
