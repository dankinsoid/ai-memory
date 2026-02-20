# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

ai-memory — long-term memory system for AI agents. Designed for years of use across many projects, making agents more personalized and capable of learning.

## Source of Truth

`claude_config_files/` contains all agent configuration that gets manually copied to `~/.claude/`:

- `prompt.md` → global `CLAUDE.md` (Memory section)
- `settings.json` → `~/.claude/settings.json`
- `skills/` → `~/.claude/skills/`
- `scripts/` → `~/.claude/hooks/`

Always edit files in this repo first, then copy to Claude settings.

## Tech Stack

- **Language:** Clojure
- **Database:** Datomic (free, Apache 2.0)
- **Infrastructure:** Docker Compose (app, DB, metrics, auth, logs)
- **Agent interface:** MCP server (local, reads ~/.claude directly)
- **Visualization:** TBD (ClojureScript frontend)

## Architecture

### Data Model — Two Layers

| Layer | Storage | Content | Size |
|-------|---------|---------|------|
| **Facts** | Datomic | title + content (1-3 sentences) + tags | ~200 bytes |
| **Blobs** | Files | Conversations, code, docs — anything too large for a fact | 1-500 KB |

Facts reference blobs via `:memory/source`. Agent reads fact first, drills into blob on demand.

### Tag Taxonomy — Primary Retrieval

Facts carry tags. Tags are organized in a navigable tree (taxonomy):

```
tags/
  languages/ (clojure, python, typescript, ...)
  patterns/ (error-handling, state-management, ...)
  projects/ (ai-memory, client-x, ...)
  preferences/ (coding-style, tooling, ...)
```

One fact → many tags from different branches. No duplication.

Three retrieval channels (all return the same facts):
1. **Taxonomy navigation** — browse tag tree → select branch → get facts
2. **Tag intersection** — query facts by tag combination
3. **Vector search** — semantic search → facts with tags → expand via taxonomy

### Graph Edges — Write-Only (Experimental)

The edge pipeline (`graph/write.clj`) continues creating edges between facts (batch, context, global). Data accumulates passively.

- **Write path:** tags + edges (both active)
- **Read path:** tags only (edges ignored for now)
- **Later:** experiment with edge-based retrieval on accumulated graph data

`graph/traverse.clj` and `decay/` are paused — not used in retrieval.

### Blob Storage — Lazy Access

```
data/blobs/{blob-id}/
  meta.edn          ← summary + chunk index (agent reads first)
  chunk-000.jsonl    ← part 1
  chunk-001.jsonl    ← part 2
```

For Claude Code sessions: MCP server reads `~/.claude/projects/.../*.jsonl` directly, extracts user/assistant messages, stores cleaned chunks.

### Memory Ingestion via MCP

Agent calls `memory_remember` after meaningful exchanges:
```
memory_remember({
  context_id: "...",
  project: "my-project",
  session_summary: "Discussed error handling approach for async pipelines",
  nodes: [{content: "...", tags: [...]}]
})
```

- Agent provides session summary + facts (~50-100 extra tokens)
- Session summary updates Datomic node + blob meta.edn
- Stop hook captures full conversation text into blob `conversation.md`

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
  - `graph/` — write pipeline (active), traversal (⚠ paused)
  - `decay/` — ⚠ paused (weight decay computation)
  - `mcp/` — MCP server for agent memory access
  - `web/` — Ring HTTP handler, REST API
- `src-ui/ai_memory/ui/` — frontend (ClojureScript + Reagent)
- `resources/schema.edn` — Datomic schema
- `data/blobs/` — blob storage (conversations, docs, code)
- `dev/user.clj` — REPL helpers
- `test/` — tests (kaocha)

## TODO Tracking

`TODO.md` — актуальный чеклист реализации проекта. При любых изменениях (новая фича, завершение задачи, смена статуса) — обновлять `TODO.md` в том же коммите.

## Architecture Decision Records

Design decisions and rationale are documented in `doc/adr/`. Read these before making architectural changes.

Key ADRs:
- **ADR-009** — Tag taxonomy as primary retrieval (supersedes graph retrieval)
- **ADR-010** — Blob storage for large content
