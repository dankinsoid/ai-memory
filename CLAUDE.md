# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

ai-memory — graph-based long-term memory system for AI agents. Memory is a weighted graph where nodes are units of knowledge (facts, decisions, preferences, patterns) and edges are associations that strengthen or decay over work cycles. Designed for years of use across many projects.

## Tech Stack

- **Language:** Clojure
- **Database:** Datomic (free, Apache 2.0) — primary candidate. Alternatives under consideration: XTDB, Postgres+pgvector
- **Infrastructure:** Docker Compose (app, DB, metrics, auth, logs)
- **Agent interface:** MCP server (compact top-k recall, server-side traversal)
- **Visualization:** Web-based graph visualization (D3.js / sigma.js — TBD), ClojureScript frontend

## Architecture

- **Graph model:** flat graph with typed nodes and hub nodes (project, domain hubs as natural aggregation points). No nested subgraphs.
- **Node granularity:** one thought per node (1-3 sentences). Atomic but meaningful.
- **Node types:** fact, decision, preference, pattern, project (hub), domain (hub). May evolve into tags.
- **Retrieval:** two-phase — vector search for entry points, then spreading activation with adaptive depth. Depth is a query parameter, not a constant.
- **Decay:** lazy, computed on access: `effective_weight = base_weight * decay_factor ^ (current_cycle - last_access_cycle)`. Time = work cycles, not wall-clock.
- **Token efficiency:** all heavy logic (traversal, vector search, decay) server-side. Agents receive only compact top-k results.

## Development

```bash
# REPL (with nREPL + CIDER middleware)
clj -M:dev

# Run app directly
clj -M:run

# Run tests (kaocha)
clj -M:test

# Run a single test namespace
clj -M:test --focus ai-memory.decay.core-test

# ClojureScript dev build (shadow-cljs)
npx shadow-cljs watch app

# Build uberjar
clj -T:build uber

# Docker
docker compose up
```

In REPL, use `(start)`, `(stop)`, `(restart)` from `dev/user.clj` to manage the system.

## Project Structure

- `src/ai_memory/` — backend (Clojure)
  - `core.clj` — entry point, system startup
  - `config.clj` — configuration from env vars
  - `db/` — Datomic connection and schema
  - `graph/` — node CRUD, edge CRUD, spreading activation traversal
  - `decay/` — weight decay computation
  - `mcp/` — MCP server for agent memory access
  - `web/` — Ring HTTP handler, REST API for visualization
- `src-ui/ai_memory/ui/` — frontend (ClojureScript + Reagent)
- `resources/schema.edn` — Datomic schema (nodes, edges, enums)
- `dev/user.clj` — REPL helpers
- `test/` — tests (kaocha)

## Architecture Decision Records

Design decisions and rationale are documented in `doc/adr/`. Read these before making architectural changes.
