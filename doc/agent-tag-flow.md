# Agent Tag Retrieval Flow

Agent retrieves facts from memory in three phases. Each phase minimizes context usage — agent controls depth and breadth of exploration.

## Overview

```
Phase 1: Navigate tag tree    → understand what memory contains
Phase 2: Estimate fact counts  → narrow tag sets to manageable size
Phase 3: Fetch facts           → get actual content
```

All three phases can happen in a single exchange if the agent already knows the taxonomy.

## Phase 1 — Tag Tree Navigation

Agent explores the tag taxonomy to find relevant branches.

### Request: browse tags

```
memory_browse_tags({ path: null, depth: 2 })
```

### Response: indented text with counts

```
languages 142
  clojure 87
  python 31
  typescript 24
patterns 95
  error-handling 18
  state-management 22
    ...
  concurrency 15
projects 68
  ...
preferences 34
  ...
```

Format: `name count`, nesting via 2-space indent. `...` as child means deeper levels exist — drill down with `path`. Agent reconstructs full tag paths from hierarchy: indented `clojure` under `languages` → `languages/clojure`.

### Drill into a branch

```
memory_browse_tags({ path: "patterns/error-handling", depth: 2 })
```

Returns only that subtree. Agent repeats until it has the tags it needs.

## Phase 2 — Estimate Fact Counts

Agent sends candidate tag combinations and gets counts without fetching actual facts.

### Request: count facts by tag sets

```
memory_count_facts({
  tag_sets: [
    ["languages/clojure", "patterns/error-handling"],
    ["languages/clojure", "patterns/concurrency"],
    ["languages/clojure"]
  ]
})
```

### Response: one line per tag set

```
languages/clojure + patterns/error-handling: 7
languages/clojure + patterns/concurrency: 3
languages/clojure: 87
```

Agent evaluates: 87 is too many, 3 is good, 7 is good. It can narrow the broad set by adding more tags, or proceed with the specific ones.

### Narrowing loop

If counts are too high, agent adds tags and re-queries:

```
memory_count_facts({
  tag_sets: [
    ["languages/clojure", "projects/ai-memory"],
    ["languages/clojure", "preferences/coding-style"]
  ]
})
```

In practice, the agent sends multiple candidate sets in the first request and at least one is usually satisfactory — single round-trip.

## Phase 3 — Fetch Facts

Agent fetches actual fact content for chosen tag sets.

### Request: get facts

```
memory_get_facts({
  tag_sets: [
    ["languages/clojure", "patterns/error-handling"],
    ["languages/clojure", "patterns/concurrency"]
  ],
  limit: 20
})
```

### Response: plain text, content only

```
= languages/clojure + patterns/error-handling
- Use ex-info for domain errors, ex-data for structured context
- Prefer try/catch at boundaries, let exceptions propagate internally

= languages/clojure + patterns/concurrency
- core.async channels for pipeline stages
- Use agents for independent state updates
```

`limit` applies per tag set. No metadata — just fact content grouped by queried tags.

## MCP Tools Summary

| Tool | Input | Output | Purpose |
|------|-------|--------|---------|
| `memory_browse_tags` | `path?`, `depth` | Indented text: `name count`, `...` = truncated | Navigate taxonomy |
| `memory_count_facts` | `tag_sets: [[str]]` | Text: `tags: count` per line | Estimate before fetching |
| `memory_get_facts` | `tag_sets: [[str]]`, `limit?` | Text: `= tags` header + `- content` lines | Get actual content |

## Design Principles

- **Agent controls context cost.** Depth limits and tag narrowing keep responses small.
- **Compact taxonomy format.** Indented text instead of JSON — zero overhead per tag node.
- **Counts are cheap.** Datomic `(count ?n)` aggregation — no entity pulls.
- **Batch by default.** Multiple tag sets per request to minimize round-trips.
- **One round-trip ideal.** Agent sends diverse tag sets in phase 2; usually at least one is good enough — skip narrowing loop.
- **Content only in facts.** No IDs, weights, or metadata — just the text the agent needs. Tags are already known from the query.
