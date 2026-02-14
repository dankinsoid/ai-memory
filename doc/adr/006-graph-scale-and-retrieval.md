# ADR-006: Graph Scale and Retrieval Strategy

**Status:** Proposed
**Date:** 2026-02-14

## Context

The graph is intended for years of use across many projects — potentially millions of nodes. Retrieval must be lazy and efficient.

## Decisions

### Two-Phase Recall

1. **Vector search** — find top-k entry point nodes by semantic similarity (or start from a known hub node)
2. **Graph traversal** — spread activation from entry points, collect relevant subgraph

### Spreading Activation with Adaptive Depth

Traversal with weight-based attenuation, not a fixed depth limit:

```
activation(next) = activation(current) * edge_weight * decay_factor
```

Continue only while `activation > threshold`. Strong chains penetrate deeper; weak ones are pruned at 1-2 hops.

### Retrieval Depth is a Query Parameter

Different questions need different traversal depth:
- **Pinpoint** ("what config format in project X?") — 1 node, 0 hops
- **Contextual** ("how do we usually handle auth?") — 2-3 hops, gather a pattern
- **Exploratory** ("what past experience is relevant for a new project?") — deep traversal for non-obvious connections

The agent or MCP server selects retrieval strategy based on query type.

### Pruning and Archival

Nodes below a weight threshold after decay are archived — excluded from active traversal but preserved in DB history. Keeps active graph manageable over years.
