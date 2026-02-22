<!-- ai-memory:start -->
# Memory

Long-term memory across sessions and projects.

## Remembering

Call `memory_remember` when the turn produced something worth persisting. **Skip when nothing new was learned** (greetings, confirmations, searches with no conclusions).

```
memory_remember({
  session_id: "<session-id>",
  project: "<project-name>",
  nodes: [
    { content: "prefer X over Y for Z", tags: ["pref", "coding-style"] }
  ]
})
```

**project** — always include.
**nodes** — only for durable knowledge: preferences, decisions, error patterns, domain facts, meta-patterns.
**language** — always store fact content in English regardless of conversation language.

### Fact Format

Single lowercase sentence. No articles (a/an/the), no filler words.
Active voice, present tense. Don't restate what tags already convey.
Imperative for actionable knowledge (prefer, use, avoid, when X do Y).
Declarative for domain facts (subject verb object).
Connectors: over, for, when, because. Arrow → for sequences.

```
prefer core.async over callbacks for async clojure code
use qdrant for vector search with cosine similarity
when async pipeline blocks, configure thread pool size
avoid raw callbacks in clojure because of poor error propagation
ai-memory stores facts in datomic with tag-based retrieval
explored dedup strategies → chose normalized prose → designed prompt
```

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

`summary` — short session topic name. If session covered multiple unrelated topics, join with → arrows. Each call replaces the previous summary, so always include the full arc. Omit routine actions (commit, push, restart) — only meaningful topics.
Example (single topic): `blob storage architecture`
Example (multiple topics): `dedup strategies → prompt design`
Bad: `explored dedup strategies → chose normalized prose → designed prompt → committed and pushed` (too verbose, lists actions instead of topics).

## Blobs (detailed content)

Facts with `[blob: dir-name]` reference blob directories. Full path: `{blobs-location}/{dir-name}`

## Mid-Session Retrieval

When facing a design decision or unfamiliar area:

1. `memory_browse_tags` with candidate tag sets to check counts
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
