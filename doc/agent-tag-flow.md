# Agent Tag Retrieval Flow

Agent retrieves facts from memory in two steps. Each step minimizes context usage — agent controls depth and breadth of exploration.

## Overview

```
Step 1: Explore tags       → understand what memory contains, estimate counts
Step 2: Fetch facts        → get actual content by tag intersection or semantic search
```

Both steps can be combined into a single exchange if the agent already knows relevant tag names.

## Step 1 — Explore Tags

Agent browses the flat tag list to discover available tags and their counts.

### Browse all tags

```
memory_explore_tags({ limit: 50 })
```

Response: one line per tag, sorted by count descending:

```
clojure 87
error-handling 18
python 31
preferences 12
ai-memory 45
...
```

Agent scans the list, identifies relevant tags, proceeds to step 2.

### Count tag intersections

Before fetching, agent can estimate how many facts match a combination:

```
memory_explore_tags({
  tag_sets: [
    ["clojure", "error-handling"],
    ["clojure"],
    ["python"]
  ]
})
```

Response: one line per set:

```
clojure + error-handling: 7
clojure: 87
python: 31
```

Agent uses counts to choose which sets to actually fetch. 87 is too many; 7 is fine.

## Step 2 — Fetch Facts

Agent fetches actual content with one or more filters. Each filter runs independently and results are returned grouped.

### Basic tag filter

```
memory_get_facts({
  filters: [
    { tags: ["clojure", "error-handling"], limit: 10 },
    { tags: ["clojure", "concurrency"], limit: 10 }
  ]
})
```

Response: grouped by filter:

```
= clojure + error-handling
- [101] Use ex-info for domain errors, ex-data for structured context
- [102] Prefer try/catch at system boundaries, let exceptions propagate internally

= clojure + concurrency
- [103] core.async channels for pipeline stages
```

### With date filter

```
memory_get_facts({
  filters: [
    { tags: ["clojure"], since: "7d", limit: 20 }
  ]
})
```

### Semantic search

```
memory_get_facts({
  filters: [
    { query: "error handling in concurrent systems", limit: 10 }
  ]
})
```

### Date-only (recent facts, all tags)

```
memory_get_facts({
  filters: [
    { since: "3d", sort_by: "date", limit: 20 }
  ]
})
```

### Sort by weight

```
memory_get_facts({
  filters: [
    { tags: ["clojure"], sort_by: "weight", limit: 20 }
  ]
})
```

`sort_by: "weight"` uses decay-adjusted effective weight — higher-reinforced, more recently accessed facts appear first.

## MCP Tools Summary

| Tool | Input | Output | Purpose |
|------|-------|--------|---------|
| `memory_explore_tags` | `limit?`, `offset?` | Text: `name count` per line | Browse flat tag list |
| `memory_explore_tags` | `tag_sets: [[str]]` | Text: `tags: count` per line | Count intersections before fetching |
| `memory_get_facts` | `filters: [{tags?, query?, since?, until?, sort_by?, limit?, id?}]` | Text: `= tags` header + `- [id] content` lines | Fetch facts by any combination |

## Design Principles

- **Agent controls context cost.** Limit and date filters keep responses small.
- **Flat tags, no tree traversal.** Agent goes straight to intersection — no hierarchy to navigate.
- **Counts are cheap.** Datomic `(count ?n)` aggregation — no entity pulls.
- **Batch by default.** Multiple filters per request to minimize round-trips.
- **Entity IDs in output.** Each fact line includes `[id]` for referencing in `memory_reinforce`.
