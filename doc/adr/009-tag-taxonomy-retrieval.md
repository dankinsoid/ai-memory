# ADR-009: Flat Tag Set as Primary Retrieval Mechanism

**Status:** Accepted (revised 2026-03-04)
**Date:** 2026-02-17
**Supersedes:** ADR-006 (graph retrieval — paused, not abandoned)

## Context

Graph-based retrieval (spreading activation over weighted edges) has drawbacks:
- Complex heuristics for traversal depth and activation thresholds
- Hard to debug ("why did the agent recall X and not Y?")
- The hypothesis that graph structure produces "intuition" is unproven

We need a retrieval mechanism that is predictable, debuggable, and token-efficient.

## Decision

**Flat atomic tags** — facts are tagged with plain strings. No hierarchy, no parent references.

Tags are stored directly on nodes as `:node/tags` (db.type/string, cardinality/many, indexed). Tag entities (`:tag/name` unique identity) exist separately and hold a materialized count (`:tag/node-count`).

### How It Works

Each fact carries multiple tags:

```
fact: "Use ex-info for domain errors"
tags: ["clojure", "error-handling", "preferences"]
```

### Two Retrieval Channels

1. **Tag exploration** — agent browses flat tag list with counts, then fetches facts by tag intersection
2. **Semantic search** — vector search (Qdrant) returns facts by embedding similarity; can be combined with tag filters

### Why Not a Hierarchy

A hierarchical taxonomy (parent tags, path strings like `languages/clojure`) adds navigation overhead — agents must traverse the tree before querying. In practice, agents already know the relevant tag names from context and go straight to intersection queries. Flat tags are simpler, equally powerful for intersection, and require zero schema complexity.

## Schema

```
:tag/name        — string, unique identity (e.g. "clojure", "error-handling")
:tag/node-count  — long, materialized count (recomputed at startup)
:tag/tier        — keyword, optional grouping (e.g. :aspect, :project, :topic)
:node/tags       — [string], many, indexed — flat tag names stored directly on node
```

## Graph Edges — Write-Only for Now

The existing edge pipeline (`graph/write.clj`) continues to create edges between facts (batch edges, context edges, global edges). This data accumulates passively.

**Read path:** tag-based only.
**Write path:** tags + edges (both).

Once enough graph data accumulates, edge-based retrieval can be experimented with alongside tags.

## Consequences

- Retrieval is predictable and debuggable
- No tree traversal overhead — agents jump straight to tag intersection
- Tag list with counts fits in one context window — cheap overview
- Facts are not duplicated — one fact, many tags
- Graph data continues to accumulate for future experiments
