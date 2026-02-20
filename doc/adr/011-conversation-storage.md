# ADR-011: Session Conversation Storage — Named Chunks

**Status:** Accepted
**Date:** 2026-02-20
**Supersedes:** ADR-010 section model for sessions (ADR-010 still applies to non-session blobs)

## Context

Sessions need lazy navigation — agent should see a meaningful TOC of what was discussed without reading everything. A single `conversation.md` file has no structure beyond turn numbers. Per-turn section files (ADR-010) create 100+ files and have no meaningful names.

## Decision

### Named chunks with `_current.md` tail

Each session blob has named chunk files plus a rolling tail:

```
data/blobs/{YYYY-MM-DD}_session-{id8}/
  meta.edn
  01-designed-blob-architecture.md    ← named chunk
  02-fixed-session-tags.md            ← named chunk
  _current.md                         ← unnamed tail (accumulates turns)
  compact.md                          ← detailed summary from /save
```

### How chunks get named

1. **UserPromptSubmit hook** fires on each user message
2. Hook syncs new conversation turns to server → server appends to `_current.md`
3. Server returns `current-chunk-size` in response
4. If `_current.md` > ~50KB, hook outputs a reminder to the agent
5. Agent calls `memory_name_chunk({context_id, title})` → server renames `_current.md` to `{NN}-{slug}.md`
6. Next sync creates a fresh `_current.md`

### _current.md format

Append-only markdown with `## Turn N` headers (same as before):

```markdown
## Turn 1 (2026-02-19T10:30:00Z)
**user**
How do I add authentication?

**assistant**
Let me explore the codebase...
```

Turn numbering is global across all chunks for a session.

### meta.edn

```clojure
{:id              #uuid "..."
 :type            :session
 :project         "ai-memory"
 :created-at      #inst "2026-02-19T..."
 :session-id      "abc12345-..."
 :session-summary "Implemented JWT auth for the API"
 :compact-summary "Detailed multi-paragraph summary..."
 :turn-count      12}
```

Chunk list is NOT stored in meta.edn — filesystem is the source of truth.

### Agent reads blobs directly

Facts show full blob path: `[blob: /home/user/.ai-memory/blobs/2026-02-19_session-abc]`. Agent uses Read/Glob/Grep on the directory directly — no `memory_read_blob` needed for sessions.

**Access pattern:**
1. Fact content (session summary from tag query)
2. `compact.md` (if `/save` was run)
3. `Glob *.md` to see chunk filenames as TOC
4. `Read` or `Grep` specific chunks

### Write pipeline

| Writer | Trigger | What it writes |
|--------|---------|----------------|
| **session-sync** | UserPromptSubmit hook → POST /api/session/sync | `_current.md` (append), meta.edn |
| **memory_remember** | Agent MCP call | Datomic session node, meta.edn (session-summary) |
| **memory_name_chunk** | Agent MCP call (when reminded) | Renames `_current.md` → `{NN}-{slug}.md` |
| **memory_session_compact** | Agent MCP call before /compact | compact.md, meta.edn (compact-summary) |

### Non-session blobs unchanged

`memory_store_file` still uses numbered section files with `memory_read_blob`. ADR-010 model applies to documents, code, and images.

## Consequences

- **Meaningful TOC** — chunk filenames describe what was discussed
- **Lazy navigation** — agent sees chunk names, reads only what's needed
- **Agent-driven** — agent names chunks when reminded, titles reflect actual content
- **Append-only** — `_current.md` is safe for incremental writes
- **Searchable** — plain markdown, greppable by agent file tools
- **Clean filesystem** — 3-10 files per session instead of 100+
