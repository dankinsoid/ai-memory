# ADR-010: Blob Storage for Large Content

**Status:** Accepted → Implemented
**Date:** 2026-02-17

## Context

Facts in Datomic are compact (1-3 sentences). But some knowledge is too detailed for a fact: full conversations, code examples, design documents, configuration snippets. We need a way to store large content with lazy access, linked from compact facts.

## Decision

### Core Model: Blob = Node

No separate blob entity. A blob is a regular node with special type (`:node.type/conversation`, `:node.type/document`) and a pointer to files on disk (`:node/blob-dir`). This reuses all existing infrastructure: tags, queries, write pipeline, MCP tools.

### Two-Layer Storage

| Layer | Storage | Content | Record size |
|-------|---------|---------|-------------|
| **Blob node** | Datomic | summary + type + date + blob-dir + tags | ~200 bytes |
| **meta.edn** | Files | Rich metadata: full summary, status, section index | ~500 bytes |
| **Sections** | Files | Actual content: conversation sections, code, docs, images | 1 KB - 500 KB |

### Schema Additions

Three attributes on the node entity + two type enums:

```clojure
:node/created-at  instant, indexed  — creation timestamp (auto-set)
:node/updated-at  instant, indexed  — last modification timestamp (auto-set)
:node/blob-dir    string            — directory name under data/blobs/
:node/sources     string, many      — blob source refs on regular facts
:node.type/conversation             — conversation blob
:node.type/document                 — generic file blob
```

Timestamps are set automatically by `create-node` (both) and updated by `reinforce-node` / `update-tag-refs` (`updated-at` only). All nodes get timestamps, not just blobs.

### File Layout

```
data/blobs/
  {YYYY-MM-DD}_{slug}/
    meta.edn                      ← summary, status, section index
    {NN}-{section-slug}.md        ← cleaned markdown sections
```

**Directory naming**: date prefix for filesystem sort, slug for readability.
**Section files**: numbered for order, slugified summary for readability.

**meta.edn (conversation):**
```clojure
{:id         #uuid "b7676aa0-..."
 :type       :conversation
 :title      "Blob storage design"
 :project    "ai-memory"
 :created-at #inst "2026-02-17T14:30:00Z"
 :summary    "Designed blob storage architecture."
 :status     "Completed. Next: implement write pipeline."
 :session-id "321eaf1b-..."
 :continues  nil
 :tags       ["projects/ai-memory" "architecture"]
 :sections
 [{:file "01-requirements.md" :summary "Requirements discussion" :lines 42}
  {:file "02-design.md"       :summary "Directory structure"     :lines 67}]}
```

### Fact → Blob Linking

Regular facts reference blob sources via `:node/sources` (cardinality many):

```clojure
{:node/sources #{"2026-02-17_blob-storage-design/02-design.md"}}
```

Format: `{blob-dir}/{section-file}`. Multiple sources allowed per fact.

### Conversation Ingestion

- Raw sessions: `~/.claude/projects/{path}/{session-id}.jsonl`
- MCP server reads JSONL, extracts user/assistant text messages
- Strips system tags, thinking blocks, tool_use/tool_result
- Splits by logical sections (agent-guided boundaries or auto-split)
- Formats as clean markdown: `**User**: ... **Assistant**: ...`

### MCP Tools

| Tool | Purpose |
|------|---------|
| `memory_list_blobs` | List blobs sorted by date, filter by type |
| `memory_read_blob` | Read meta or specific section content |
| `memory_store_conversation` | Store conversation from session JSONL |
| `memory_store_file` | Store generic file as blob |

Lazy access: list → read meta → read section.

### Agent Access Flow

```
Path A — tags:  get_facts → see blob summary → read_blob(section)
Path B — list:  list_blobs(type=conversation) → read_blob → read_blob(section)
Path C — drill: get_facts → fact with [src: ...] → read_blob(section)
```

## Consequences

- Datomic stays lean — blob nodes are regular nodes with extra attributes
- Blob summaries appear in tag queries alongside regular facts
- Lazy access: browse → metadata → specific section
- No extra LLM calls — agent provides summaries
- Extensible to any content type
- Date-sorted directories for human browsing
