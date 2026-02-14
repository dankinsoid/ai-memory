# ADR-003: Database Choice

**Status:** Proposed
**Date:** 2026-02-14

## Context

The memory system is a weighted graph with temporal properties (decay, history). Need a database that supports:

1. Graph structure with weighted edges
2. Efficient traversal for deep associations
3. Temporal queries (history of memory states, rollback)
4. Eventually — vector similarity search for semantic recall

## Options Considered

### Datomic
- **Pros:** Immutable, bitemporal out of the box, Clojure-native, Datalog for graph queries, entity references = natural graph, **free under Apache 2.0 since April 2023** (all editions, available via Maven Central, no signup).
- **Cons:** No built-in vector search. Cloud edition tied to AWS.

### XTDB (ex-Crux)
- **Pros:** Open source, Clojure-native, bitemporal, Datalog, V2 adds SQL. More flexible deployment than Datomic Cloud.
- **Cons:** No built-in vector search. Younger ecosystem.

### Neo4j
- **Pros:** Best-in-class graph traversal (5+ hops), Cypher is expressive.
- **Cons:** Weak Clojure integration, no bitemporality, would need custom temporal layer.

### Postgres + pgvector
- **Pros:** Vector search built-in, mature, good tooling.
- **Cons:** Graph traversals via recursive CTEs are awkward and slow for deep associations.

## Current Thinking

**Datomic** or **XTDB** as the primary store for the graph structure + temporal history. Vector search can be handled separately (pgvector sidecar, or in-process HNSW via Java interop) since it's only needed for semantic recall, not for storing the graph itself.

Final decision deferred until prototyping phase.
