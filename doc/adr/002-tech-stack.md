# ADR-002: Tech Stack

**Status:** Accepted
**Date:** 2026-02-14

## Context

Need to choose core technologies for the memory system.

## Decision

- **Language:** Clojure
- **Infrastructure:** Docker Compose (app, DB, metrics, auth, logs)
- **Visualization:** Web-based graph visualization (D3.js / sigma.js — TBD)

## Consequences

Clojure gives excellent Java interop (useful for vector indexing, DB drivers) and is a natural fit for Datomic/XTDB. Docker Compose simplifies local dev and deployment of the full stack.
