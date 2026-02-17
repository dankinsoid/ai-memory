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

### Response: depth-limited tree with counts

```json
{
  "tree": [
    { "path": "languages", "count": 142, "children": [
      { "path": "languages/clojure", "count": 87 },
      { "path": "languages/python", "count": 31 },
      { "path": "languages/typescript", "count": 24 }
    ]},
    { "path": "patterns", "count": 95, "children": [
      { "path": "patterns/error-handling", "count": 18 },
      { "path": "patterns/state-management", "count": 22 },
      { "path": "patterns/concurrency", "count": 15 }
    ]},
    { "path": "projects", "count": 68, "children": ["..."] },
    { "path": "preferences", "count": 34, "children": ["..."] }
  ],
  "truncated": true
}
```

`depth` limits how many levels to return. `truncated: true` signals there are deeper nodes the agent can drill into.

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

### Response: count per tag set

```json
{
  "counts": [
    { "tags": ["languages/clojure", "patterns/error-handling"], "count": 7 },
    { "tags": ["languages/clojure", "patterns/concurrency"],   "count": 3 },
    { "tags": ["languages/clojure"],                           "count": 87 }
  ]
}
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

### Response: facts grouped by tag set

```json
{
  "results": [
    {
      "tags": ["languages/clojure", "patterns/error-handling"],
      "facts": [
        { "id": "...", "title": "...", "content": "...", "tags": ["languages/clojure", "patterns/error-handling", "projects/ai-memory"] },
        "..."
      ]
    },
    {
      "tags": ["languages/clojure", "patterns/concurrency"],
      "facts": ["..."]
    }
  ]
}
```

`limit` applies per tag set. Facts include all their tags (not just the queried ones) so agent sees full context.

## MCP Tools Summary

| Tool | Input | Output | Purpose |
|------|-------|--------|---------|
| `memory_browse_tags` | `path?`, `depth` | Tree with counts, `truncated` flag | Navigate taxonomy |
| `memory_count_facts` | `tag_sets: [[str]]` | Count per tag set | Estimate before fetching |
| `memory_get_facts` | `tag_sets: [[str]]`, `limit?` | Facts grouped by tag set | Get actual content |

## Design Principles

- **Agent controls context cost.** Depth limits and tag narrowing keep responses small.
- **Counts are cheap.** Datomic `(count ?n)` aggregation — no entity pulls.
- **Batch by default.** Multiple tag sets per request to minimize round-trips.
- **One round-trip ideal.** Agent sends diverse tag sets in phase 2; usually at least one is good enough — skip narrowing loop.
- **Facts carry all tags.** Response includes every tag on a fact, not just the queried ones. Agent can discover related tags without extra queries.
