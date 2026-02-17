# ADR-009: Tag Taxonomy as Primary Retrieval Mechanism

**Status:** Accepted
**Date:** 2026-02-17
**Supersedes:** ADR-006 (graph retrieval — paused, not abandoned)

## Context

Graph-based retrieval (spreading activation over weighted edges) has drawbacks:
- Complex heuristics for traversal depth and activation thresholds
- Hard to debug ("why did the agent recall X and not Y?")
- The hypothesis that graph structure produces "intuition" is unproven

Meanwhile, AI agents already navigate tree structures effectively (filesystem, project directories). We need a retrieval mechanism that is predictable, debuggable, and token-efficient.

## Decision

**Tag taxonomy** — a tree of tags used for navigating and filtering facts.

### How It Works

Facts are stored as nodes (existing schema: `:node/content`, `:node/tags`). Tags are organized in a hierarchy (taxonomy):

```
tags/
  languages/
    clojure
    python
    typescript
  patterns/
    error-handling
    state-management
    concurrency
  projects/
    ai-memory
    client-x
  preferences/
    coding-style
    tooling
```

Each fact carries multiple tags from different branches. One fact, one record, multiple access paths.

### Three Retrieval Channels

All three return the same facts in the same format — different entry points:

1. **Taxonomy navigation** — agent browses the tag tree, selects a branch, gets facts
2. **Tag intersection** — agent queries facts matching a combination of tags
3. **Vector search** — semantic search returns facts with their tags and taxonomy path; agent can then expand context via tags

### Why Not Pure Tree (Facts as Tree Nodes)

A fact like "I prefer functional style in Clojure" belongs in preferences/ AND languages/clojure/. A tree forces one location or duplication. Tag taxonomy separates organization (tag tree) from content (facts), allowing many-to-many relationships.

### Why Not Flat Tags (No Hierarchy)

Without hierarchy, tag lists grow unmanageable (hundreds of tags). The taxonomy provides navigable structure — agents browse categories before selecting specific tags. The tag tree itself is small enough to fit in a single context window.

## Graph Edges — Write-Only for Now

The existing edge pipeline (`graph/write.clj`) continues to create edges between facts (batch edges, context edges, global edges). This data accumulates passively.

**Read path:** tag-based only.
**Write path:** tags + edges (both).

Once enough graph data accumulates, we can experiment with edge-based retrieval alongside tags. The two approaches are not mutually exclusive — same facts, different retrieval strategies.

## Schema Changes

Add tag taxonomy to existing schema:

```
tag:
  :tag/id      — uuid, unique identity
  :tag/name    — string, e.g. "clojure"
  :tag/parent  — ref to parent tag (nil = root)
  :tag/path    — string, materialized path e.g. "languages/clojure"
```

Existing `:node/tags` (many strings) remains for backward compatibility. New facts reference tag entities via refs for taxonomy navigation.

## Consequences

- Retrieval is predictable and debuggable
- Agents navigate memory like a filesystem — zero learning curve
- Tag tree fits in one context window — cheap overview
- Facts are not duplicated — one fact, many tags
- Graph data continues to accumulate for future experiments
