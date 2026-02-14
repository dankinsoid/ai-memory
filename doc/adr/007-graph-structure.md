# ADR-007: Graph Structure — Typed Nodes with Hubs

**Status:** Proposed
**Date:** 2026-02-14

## Context

Should the graph be flat (all nodes equal) or have some higher-level structure?

## Decision

**Typed nodes with hub nodes** in a single flat graph (no nested subgraphs).

### Why Not Nested/Hierarchical

Rigid boundaries cause problems when knowledge crosses projects. A fact like "Clojure is good for data-oriented systems" may relate to five projects — nesting forces duplication.

### Hub Nodes

Certain node types naturally aggregate connections and serve as fast entry points:
- `project:ai-memory` — start here to get everything about the project
- `domain:databases` — start here to get everything about DB knowledge

Hubs eliminate the need for vector search on simple scoped queries ("tell me about project X").

### Node Types (Initial Set, Will Evolve)

- **fact** — objective information
- **decision** — a decision made and why
- **preference** — user or agent preference
- **pattern** — recurring approach or pattern
- **project** — project hub
- **domain** — subject area hub (e.g. "databases", "auth")

Whether these are strict types or free-form tags — TBD.

### Algorithmic Clustering

Beyond explicit hubs, clusters can be detected algorithmically from graph topology (community detection) rather than manually designed. Useful for visualization and discovery.

## Node Granularity

One thought per node — expressible in 1-3 sentences. Atomic but meaningful.

Too fine (single trivial facts) creates noise. Too coarse (whole documents) destroys associative power.
