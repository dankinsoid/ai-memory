# ADR-010: Blob Storage for Large Content

**Status:** Accepted
**Date:** 2026-02-17

## Context

Facts in Datomic are compact (1-3 sentences). But some knowledge is too detailed for a fact: full conversations, code examples, design documents, configuration snippets. We need a way to store large content with lazy access, linked from compact facts.

## Decision

### Two-Layer Storage

| Layer | Storage | Content | Record size |
|-------|---------|---------|-------------|
| **Facts** | Datomic | title + content (1-3 sentences) + tags | ~200 bytes |
| **Blobs** | Files | Conversations, code, docs, configs — anything too large for a fact | ~1 KB - 500 KB |

### File Format

Each blob is a directory with `meta.edn` (summary + chunk index) and chunk files:

```
data/blobs/{blob-id}/
  meta.edn          ← summary + chunk index (agent reads first)
  chunk-000.jsonl    ← part 1
  chunk-001.jsonl    ← part 2
  ...
```

**meta.edn:**
```clojure
{:id        "b7676aa0-..."
 :type      :conversation          ;; :conversation, :code, :document, ...
 :project   "ai-memory"
 :date      #inst "2026-02-17"
 :summary   "Discussion: graph vs tree memory"
 :chunks
 [{:file "chunk-000.jsonl" :summary "Comparing graph vs tree"}
  {:file "chunk-001.jsonl" :summary "Tag taxonomy design"}]}
```

Lazy access: agent reads `meta.edn` (~100 bytes), selects a chunk, reads only that chunk.

### Source References from Facts

```clojure
{:memory/source
 {:blob-id "b7676aa0-..."
  :chunk   1}}
```

### Conversation Ingestion

For Claude Code sessions specifically:

- Raw sessions live in `~/.claude/projects/{path}/{session-id}.jsonl`
- MCP server runs locally — reads JSONL files directly from filesystem
- Extracts user/assistant messages, discards noise (progress, snapshots)
- Stores cleaned chunks in blob storage

Agent calls `memory_remember` with summary + facts (~50-100 tokens). MCP server reads full message text from `~/.claude` JSONL — zero token cost for the full text.

### Other Blob Types

Same storage model works for any large content:
- Code snippets with explanation
- Architecture documents
- Configuration examples
- Error logs with analysis

## Consequences

- Datomic stays lean — only compact facts with tags
- Large content accessible on demand via blob references
- Lazy access: summary → chunk selection → chunk read
- No extra LLM calls — the calling agent provides summaries
- Extensible to any content type, not just conversations
