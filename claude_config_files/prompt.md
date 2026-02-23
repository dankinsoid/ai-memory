<!-- ai-memory:start -->
# Memory

Long-term memory across sessions and projects.

## When to Remember

**Strongest triggers** (almost always remember):
- User feedback, corrections, preferences — "I prefer X", "don't do Y", "that was wrong"
- Error patterns — what went wrong, root cause, fix
- Non-obvious gotchas — things that surprised you or contradicted expectations

**Good triggers** (remember if durable):
- Decisions with rationale — the WHY, not the what
- Recurring patterns and approaches
- Domain knowledge not obvious from code

**Skip** (do not remember):
- Implementation details recoverable from code (function signatures, file structure, config values)
- Routine operations (committed, pushed, restarted, deployed)
- Decisions on minor questions that may change soon
- Anything that restates documentation or code comments
- Greetings, confirmations, searches with no conclusions

**Test**: would this fact be useful in a *different* session months later? If only useful right now — skip.

Call `memory_remember` when the turn produced something worth persisting.
**project** — always include. **language** — always English regardless of conversation language.

### Fact Format

One short sentence, max 15 words. Every word must earn its place.
Lowercase. No articles (a/an/the). No filler words.
**Always a sentence** — consistent format is critical for deduplication.
Imperative for actionable knowledge — written as instruction to future agent: prefer, use, avoid, when X do Y.
Declarative for domain facts: subject verb object.

```
prefer core.async over callbacks for async clojure code
prefer short commit messages
when async pipeline blocks, configure thread pool size
avoid raw callbacks in clojure — poor error propagation
qdrant uses cosine similarity for vector search
edn config parsing silently drops duplicate keys
```

Bad (not a sentence): `ai-memory: datomic facts, tag retrieval, weight decay`
Bad (too long): `ai-memory system stores all facts in datomic database and uses tag-based retrieval combined with time-weighted decay for ranking`
Good: `ai-memory stores facts in datomic with tag retrieval and weight decay`

### Abstraction Levels

- **Concrete**: specific technical fact
- **Pattern**: recurring approach or preference
- **Meta**: underlying philosophy or principle

When 3+ concrete facts share a theme — synthesize a meta-fact.

## Tags

Each tag is a retrieval dimension. More tags = more precise search narrowing.

Two kinds:

**Aspect** — fixed vocabulary from Memory Context. Pick 1-2 per fact.
**Free-form** — projects, technologies, domains, contexts: `datomic`, `swift`, `react`, `async`. Pick 2-3 per fact. Prefer existing — browse before creating new.

**Rules**:
- Every fact: 1-2 aspect + 2-3 free-form = **3-5 tags total**
- Project-specific facts MUST include project name tag (e.g. `ai-memory`)
- `universal` — for facts relevant across all projects
- Kebab-case, atomic strings
- Think: "what would I search for to find this fact?" — those are your tags

Examples:
```
"prefer reagent over rum for react UIs"
  → [preference, clojure, react, reagent]

"edn config parsing silently drops duplicate keys"
  → [pitfall, clojure, edn, config]

"prefer short commit messages"
  → [preference, universal, workflow, git]
```

## Reinforcing

After completing a task where retrieved facts influenced your work, call `memory_reinforce`.
**Score**: -1 (actively misled) to 1 (essential, directly unblocked task).
Only facts with **direct impact**. Retrieved but unused = skip. Score near 0 = skip.

## Session Metadata — `memory_session`

Hook reminders tell you when to call and which params to include.

`summary` — answer "what was this session about?" in 2-5 words. Each call replaces previous, so include full arc. If truly multiple unrelated topics, join with → (max once).
Good: `blob storage architecture`
Good: `prompt rewrite → tag system`
Bad: `explored dedup strategies → chose normalized prose → designed prompt → committed` (too detailed, lists steps not topics)

## Blobs

Facts with `[blob: dir-name]` reference blob directories. Read with `memory_read_blob`.
Common commands: `ls`, `cat compact.md`, `cat _current.md`, `head -50 0001-*.md`.

## Mid-Session Retrieval

When facing a design decision or unfamiliar area:

1. `memory_explore_tags` with candidate tag sets to check counts
2. `memory_get_facts` if counts manageable

Supports semantic search via `query` param — use when you know *what* but not *how it's tagged*.
Combine `query` with `tags` to narrow scope.
<!-- ai-memory:end -->
