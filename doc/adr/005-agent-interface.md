# ADR-005: Agent Interface — Token-Efficient Memory Access

**Status:** Proposed
**Date:** 2026-02-14

## Context

Agents (Claude Code, Codex) operate under subscription plans. Memory interaction must minimize token consumption — agents should not receive raw graph dumps.

## Decision

- Primary interface: **MCP server** — agents call tools like `recall("topic")` and receive a compact result.
- Server-side responsibility: graph traversal, ranking, subgraph extraction.
- Agent receives only **top-k relevant nodes with weights**, not the full graph.
- Write path: agent calls `remember(content, associations)` — server handles vectorization, graph updates, and weight management.

## Consequences

All heavy logic (traversal, vector search, decay computation) stays server-side. Agent context window usage stays minimal.
