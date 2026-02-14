# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

ai-memory — graph-based long-term memory system for AI agents. Memory is a weighted graph where nodes are units of knowledge (facts, decisions, preferences, patterns) and edges are associations that strengthen or decay over work cycles. Designed for years of use across many projects.

## Tech Stack

- **Language:** Clojure
- **Database:** Datomic (free, Apache 2.0) — primary candidate. Alternatives under consideration: XTDB, Postgres+pgvector
- **Infrastructure:** Docker Compose (app, DB, metrics, auth, logs)
- **Agent interface:** MCP server (compact top-k recall, server-side traversal)
- **Visualization:** Web-based graph visualization (D3.js / sigma.js — TBD)

## Architecture

- **Graph model:** flat graph with typed nodes and hub nodes (project, domain hubs as natural aggregation points). No nested subgraphs.
- **Node granularity:** one thought per node (1-3 sentences). Atomic but meaningful.
- **Node types:** fact, decision, preference, pattern, project (hub), domain (hub). May evolve into tags.
- **Retrieval:** two-phase — vector search for entry points, then spreading activation with adaptive depth. Depth is a query parameter, not a constant.
- **Decay:** lazy, computed on access: `effective_weight = base_weight * decay_factor ^ (current_cycle - last_access_cycle)`. Time = work cycles, not wall-clock.
- **Token efficiency:** all heavy logic (traversal, vector search, decay) server-side. Agents receive only compact top-k results.

## Architecture Decision Records

Design decisions and rationale are documented in `doc/adr/`. Read these before making architectural changes.
