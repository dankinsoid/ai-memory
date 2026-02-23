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
    { content: "prefer X over Y for Z", tags: ["preference", "clojure"] }
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

## Reinforcing

After completing a task where retrieved facts influenced your work, call `memory_reinforce` to provide learning signal.

```
memory_reinforce({
  reinforcements: [
    { id: 12345, score: 0.8 },
    { id: 12346, score: -0.5 }
  ]
})
```

**Score**: -1 (misleading, caused wrong approach) to 1 (essential, directly unblocked task).

**Rules**:
- Only facts that had **direct impact** on the task outcome. Retrieved but unused = skip.
- Be selective: if 10 facts were retrieved, typically 1-3 actually mattered.
- Negative scores: only when a fact actively misled you (wrong approach, outdated info).
- Don't reinforce with score near 0 — just omit those facts.
- Fact IDs come from `[id]` prefix in `memory_get_facts` output.

### Fact Quality

Good: self-contained, specific, has rationale.
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

Facts with `[blob: dir-name]` reference blob directories. Read with `memory_read_blob`:

`memory_read_blob({ blob_dir: "dir-name", command: "cat compact.md" })`

Common: `ls` (list files), `cat compact.md` (summary), `cat _current.md` (latest), `head -50 0001-*.md` (preview).

## Mid-Session Retrieval

When facing a design decision or unfamiliar area:

1. `memory_browse_tags` with candidate tag sets to check counts
2. `memory_get_facts` if counts manageable

Filters support semantic search via `query` — use when you know *what* you're looking for but not *how it's tagged*:

`memory_get_facts({ filters: [{ query: "error handling in async pipelines", limit: 10 }] })`

Combine with tags to scope: `{ tags: ["clj"], query: "async", limit: 10 }`

## Tags

Two kinds:

**Aspect** — fixed vocabulary shown in Memory Context at session start. Describes what kind of knowledge: `architecture`, `pattern`, `decision`, etc. Pick 1-2 per fact.
**Free-form** — projects, technologies, domains: `datomic`, `swift`, `react`. Add 0-2 per fact. Prefer existing — browse before creating.

**Rules**:
- Every fact: 1-2 aspect + 0-2 free-form = 2-4 tags total (project tag auto-added by system)
- `universal` — for facts relevant in every session regardless of project
- Kebab-case, atomic strings
- Query by intersection: `["ai-memory", "architecture"]` = facts tagged with BOTH
<!-- ai-memory:end -->
