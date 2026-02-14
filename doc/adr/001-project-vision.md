# ADR-001: Project Vision — Long-Term Memory for AI Agents

**Status:** Accepted
**Date:** 2026-02-14

## Context

AI agents (Claude Code, Codex, etc.) lack persistent long-term memory between sessions. We want a system that stores, strengthens, and decays knowledge over time — similar to how biological memory works.

## Decision

Build a **graph-based memory database** for AI agents with the following properties:

- **Memory as a weighted graph** — nodes are units of information (facts, preferences, approaches, patterns), edges are associations between them.
- **Edges have weights** — connections can be created, strengthened, and weakened.
- **Natural decay** — weights weaken over time if not reinforced. Time is measured in **work cycles** (logical ticks of agent activity), not wall-clock time.
- **Future: learning layer** — the system should eventually evaluate stored knowledge, allowing agents to gravitate toward or avoid certain approaches based on experience.

## Target Consumers

- Claude Code (via MCP server)
- Codex
- Other AI agents via API

## Token Efficiency

A core constraint: agents work under subscription plans (Claude Code Max, Codex). Memory interaction must be **compact** — agents should not load the full graph into context. The interface should return only relevant subgraphs (top-k nodes with weights).
