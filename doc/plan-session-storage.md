# Plan: Mandatory Session Storage via Stop Hook

## Context

MCP integration is done (9 tools, registered in settings.json, CLAUDE.md instructions). Now: **make session JSONL storage mandatory** via Claude Code `Stop` hook.

**Problem:** Currently `memory_store_conversation` is called manually by the agent (unreliable). Session storage must be automatic — every response captured regardless of agent cooperation.

**Architecture:**
- **Agent** (via `memory_remember`): writes `turn_summary` + `session_summary` + facts — cheap, agent already has context
- **Hook** (`Stop`): parses JSONL locally, sends only new messages to server — mechanical, no LLM
- **Server** links summaries to message sections by timestamp matching

**Key constraint:** No re-sending messages to LLM for summarization — agent writes all summaries itself via MCP during its natural response (just output tokens).

**Two summary versions:**
- **Short** (fact content): agent provides `session_summary` — 1 sentence, searchable via tags
- **Detailed** (blob meta): server concatenates accumulated `turn_summary` entries — zero agent cost

## Requirements

1. Session JSONL storage is mandatory — done via `Stop` hook, not optional agent behavior
2. Turn summaries → blob section metadata, paired with content (NOT independent arrays)
3. Overall conversation summary → both fact AND blob meta, updates each turn
4. Two versions: short (1 sentence, fact) + detailed (concatenated turn_summaries, blob meta)
5. No re-sending messages to LLM for summarization — agent writes summaries via MCP
6. MCP can be remote; hook is always local (parses JSONL, sends only delta)
7. Hook sends only new messages since last sync (not entire dialog)
8. v1: MCP local (shared RAM for turn_summary buffer). v2: buffer in Datomic

## Deployment Topology

```
v1 (local dev):
  Claude Code → MCP (stdio, local JVM)
                ├── Datomic (in-memory, same JVM)
                ├── Blob storage (local files)
                └── HTTP server (Jetty, same JVM, for hooks)
  Stop hook (local) → parses JSONL → POST delta → localhost HTTP

v2 (remote):
  Claude Code → MCP (streamable HTTP, remote server)
                ├── Datomic (transactor, remote)
                └── Blob storage (remote)
  Stop hook (local) → parses JSONL → POST delta → remote HTTP
```

---

## JSONL Format (Claude Code)

Each JSONL line is a JSON object. Relevant entry types:

```json
{"type": "user",      "sessionId": "830e6ad1-...", "uuid": "46b01...", "parentUuid": null,
 "timestamp": "2026-02-17T09:49:09.592Z", "cwd": "/Users/.../ai-memory",
 "message": {"role": "user", "content": "..."}}

{"type": "assistant", "sessionId": "830e6ad1-...", "uuid": "77aae...", "parentUuid": "46b01...",
 "timestamp": "2026-02-17T09:49:12.105Z",
 "message": {"role": "assistant", "content": [{"type": "text", "text": "..."}]}}
```

Key fields: `type` (user/assistant/queue-operation/file-history-snapshot), `uuid`, `parentUuid` (chain), `timestamp` (ISO 8601), `sessionId`.

One "turn" = many JSONL lines (user text → assistant tool_use → user tool_result → ... → assistant text).

Skip non-message types: `queue-operation`, `file-history-snapshot`.

---

## Data Flow

### Per-turn (during agent response):

```
Agent calls memory_remember({
  session_id: "session-123",
  turn_summary: "User: hook architecture → designed Stop hook + HTTP sync",
  session_summary: "Integrating ai-memory session storage via Stop hook",
  nodes: [{ content: "...", tags: [...] }]
})
↓
Server (MCP handler):
  1. nodes → write pipeline (facts, dedup, edges) [unchanged]
  2. turn_summary + timestamp(now) → append to RAM buffer[session-123]
  3. session_summary → upsert session fact (type :session, proj/* tag)
```

### After response (Stop hook):

```
Stop hook fires → receives stdin: {session_id, transcript_path, cwd}
↓
Hook script (local):
  1. Read state file: ~/.claude/hooks/state/{session_id}.json → {last_uuid}
  2. Parse JSONL from transcript_path
  3. Find last_uuid in JSONL, take everything after it
     (if not found — /clear happened — send all)
  4. Filter: only type "user" and "assistant" entries
  5. POST /api/session/sync:
     {session_id, cwd, messages: [{uuid, timestamp, role, content}, ...]}
  6. Save new last_uuid to state file
↓
Server (HTTP handler):
  1. Find or create blob for session_id
  2. Group messages into turn (user text → ... → assistant final text)
  3. Consume matching turn_summaries from RAM buffer by timestamp:
     turn_summary.timestamp falls between turn's first and last message timestamps
  4. Create new blob section: {summary: matched_turn_summary, content: messages}
  5. Append section file + update meta.edn
  6. Link session fact → blob (set :node/blob-dir)
```

### Timestamp matching

```
Timeline of one turn:
  T1 — user message written to JSONL
       ...agent thinks, calls tools...
  T2 — agent calls memory_remember → MCP records timestamp = now()
       ...agent finishes response...
  T3 — assistant message written to JSONL
  T4 — Stop hook fires

Guarantee: T1 < T2 < T3
Match: turn_summary where T1 < turn_summary.timestamp < T3
```

### Blob structure (lazy loading)

```
data/blobs/{blob-id}/
  meta.edn
  section-000.md
  section-001.md
  ...
```

```clojure
;; meta.edn
{:session-id "830e6ad1-..."
 :project    "ai-memory"
 :summary    "Проектирование session storage для ai-memory"  ;; session_summary
 :sections
 [{:id 0
   :summary "Hook architecture → designed Stop hook + HTTP sync"
   :timestamp "2026-02-17T09:49:12Z"
   :file "section-000.md"}
  {:id 1
   :summary "Deployment topology → chose remote MCP"
   :timestamp "2026-02-17T10:15:00Z"
   :file "section-001.md"}]}
```

Agent reads meta.edn → picks section by summary → loads only that section file.

### What goes where

| Data | Storage | Source |
|------|---------|--------|
| Facts (preferences, decisions) | Datomic nodes + tags | Agent → `memory_remember` nodes |
| Turn summary | Blob section `:summary` in meta.edn | Agent → `memory_remember` turn_summary, matched by timestamp |
| Session summary (short) | Datomic session fact `:node/content` | Agent → `memory_remember` session_summary |
| Session summary (detailed) | Blob `meta.edn` `:summary` field | Server joins section summaries |
| Raw conversation per turn | Blob section files (section-NNN.md) | Hook parses JSONL → sends delta → server writes section |
| Sync state | `~/.claude/hooks/state/{session-id}.json` | Hook tracks last synced UUID locally |

### Edge cases

- **Agent forgets `memory_remember`:** Hook still sends messages. Section created without summary. Raw conversation preserved, browsable but not summarized.
- **Hook fails:** Facts and session summary still saved via MCP. No blob sections, but searchable facts exist.
- **`/clear` resets context:** Hook can't find last_uuid in JSONL. Falls back to sending all messages. Server deduplicates sections by UUID range.
- **Multiple rapid `Stop` fires:** Each sync sends only delta since last_uuid. Safe, no duplicate work.
- **No timestamp match for turn_summary:** Section gets no summary. Turn summary stays in buffer, evicted by TTL.

---

## Implementation Steps

### Step 1. Schema: add `:node/session-id`

New attribute to find existing blob/fact for a session:

```clojure
{:db/ident       :node/session-id
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/index       true
 :db/doc         "Claude Code session ID. Links session facts and blobs to sessions."}
```

New node type enum `:node.type/session` (rolling summary fact, distinct from `:node.type/conversation` for stored blobs).

File: `resources/schema.edn`

### Step 2. Turn summary buffer in RAM

New namespace `mcp/session.clj`:

```clojure
;; {session-id {:entries [{:summary "..." :timestamp #inst "..."}]
;;              :last-access #inst "..."}}
(def ^:private session-buffer (atom {}))
```

Each entry stores summary + timestamp (recorded at `memory_remember` call time).

Functions:
- `append-turn-summary! [session-id summary]` — append `{:summary summary :timestamp (Instant/now)}` to buffer
- `get-turn-summaries [session-id]` — return accumulated entries
- `consume-turn-summaries! [session-id]` — return and clear (atomic)
- `match-summary [entries t-start t-end]` — find entry where `t-start < timestamp < t-end`

TTL eviction (reuse pattern from `graph/write.clj` contexts).

File: `src/ai_memory/mcp/session.clj` (new)

### Step 3. Modify `handle-remember`

Current: stores turn_summary as `:conversation` node in Datomic (wrong).

New:
1. `nodes` → write pipeline (unchanged)
2. `turn_summary` → `session/append-turn-summary!` (RAM buffer, NOT a fact)
3. `session_summary` → find-or-create session fact:
   - Query: `[:find ?e . :where [?e :node/session-id session-id]]`
   - If exists: update `:node/content` with new session_summary
   - If not: create node `{:node-type :session, :content session_summary, :session-id session-id, :tag-refs [proj/*]}`

File: `src/ai_memory/mcp/server.clj` — `handle-remember`

### Step 4. Hook script — Babashka (local JSONL parsing + delta sync)

Requires: [Babashka](https://babashka.org/) (`brew install babashka`). ~10ms startup, built-in JSON + HTTP.

Hook script `~/.claude/hooks/session-sync.bb` — runs locally after every agent response:

```clojure
#!/usr/bin/env bb
(require '[cheshire.core :as json]
         '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[clojure.java.io :as io])

(let [input      (json/parse-string (slurp *in*) true)
      session-id (:session_id input)
      transcript (:transcript_path input)
      cwd        (:cwd input)
      state-dir  (str (System/getenv "HOME") "/.claude/hooks/state")
      state-file (str state-dir "/" session-id ".edn")
      port       (or (System/getenv "AI_MEMORY_PORT") "8080")

      ;; Read last synced UUID
      last-uuid  (when (fs/exists? state-file)
                   (:last-uuid (read-string (slurp state-file))))

      ;; Parse JSONL, take messages after last-uuid
      lines      (line-seq (io/reader transcript))
      entries    (map #(json/parse-string % true) lines)
      after-last (if last-uuid
                   (->> entries
                        (drop-while #(not= (:uuid %) last-uuid))
                        rest)  ; drop the last-uuid entry itself
                   entries)    ; no state → send all
      messages   (->> after-last
                      (filter #(#{"user" "assistant"} (:type %)))
                      (mapv (fn [e]
                              {:uuid      (:uuid e)
                               :timestamp (:timestamp e)
                               :type      (:type e)
                               :role      (get-in e [:message :role])
                               :content   (get-in e [:message :content])})))]

  (when (seq messages)
    ;; POST delta to server
    (http/post (str "http://localhost:" port "/api/session/sync")
               {:headers {"Content-Type" "application/json"}
                :body    (json/generate-string
                           {:session_id session-id
                            :cwd        cwd
                            :messages   messages})})
    ;; Save sync state
    (fs/create-dirs state-dir)
    (spit state-file (pr-str {:last-uuid (:uuid (last messages))}))))
```

File: `~/.claude/hooks/session-sync.bb` (new)

### Step 5. Session sync endpoint (server-side)

New function `handle-session-sync`:

Input (from hook): `{:session-id "..." :cwd "/project/path" :messages [{:uuid :timestamp :role :content} ...]}`

Logic:
1. Find existing blob: query Datomic for node with `:node/session-id` + `:node/blob-dir`
2. Derive project from `cwd` (last path segment)
3. **Group messages into turns:** split by user-text boundaries (user text → ... → assistant final text = one turn)
4. **For each turn, match turn_summary by timestamp:**
   - Get buffered summaries: `session/get-turn-summaries`
   - For each turn: find summary where `turn[first].timestamp < summary.timestamp < turn[last].timestamp`
   - Consume matched summaries from buffer
5. **Append to blob:**
   - If no blob: create directory, initialize meta.edn
   - For each turn: write `section-{N}.md`, append to meta.edn sections with matched summary
6. **Create or update blob node in Datomic:**
   - If no blob node: create `{:node-type :conversation, :blob-dir blob-dir, :session-id session-id}`
   - If exists: update `:node/updated-at`
7. **Link session fact → blob:** set `:node/blob-dir` on the session fact

Files:
- `src/ai_memory/mcp/server.clj` — new `handle-session-sync`
- `src/ai_memory/web/api.clj` — new `session-sync` handler
- `src/ai_memory/web/handler.clj` — add route `["/session/sync" {:post ...}]`

### Step 6. MCP main starts HTTP server alongside stdio

Modify `mcp/main.clj` to start Jetty in background before entering stdio loop:

```clojure
(defn -main [& _args]
  (let [cfg  (-> (config/load-config) (assoc :metrics (metrics/create-registry)))
        conn (db/connect (:datomic-uri cfg))]
    (db/ensure-schema conn)
    ;; HTTP server in background (Jetty :join? false) — for hooks
    (web/start {:port (:port cfg) :conn conn :cfg cfg})
    (log/info "ai-memory MCP+HTTP server starting, HTTP port" (:port cfg))
    ;; MCP stdio loop blocks main thread
    (transport/run-loop (protocol/make-handler conn cfg))))
```

Same Datomic conn, same cfg, shared RAM. Jetty runs on thread pool.

File: `src/ai_memory/mcp/main.clj`

### Step 7. Hook configuration

Hook config in `~/.claude/settings.json`:
```json
{
  "hooks": {
    "Stop": [{
      "type": "command",
      "command": "bb ~/.claude/hooks/session-sync.bb",
      "timeout": 30
    }]
  }
}
```

File: `~/.claude/settings.json`

### Step 8. Update `memory_remember` tool schema

Add `session_summary` parameter:

```clojure
:session_summary {:type "string"
                  :description "Rolling 1-sentence session summary (updated each turn). Stored as searchable fact."}
```

Update `convert-params` to pass `:session-summary`.

File: `src/ai_memory/mcp/protocol.clj`

### Step 9. Update prompts

Update `doc/memory-prompt.md` and sync to `~/.claude/CLAUDE.md`:

```
memory_remember({
  session_id: "<session-id>",
  turn_summary: "User: <request gist> → <what I did/decided>",
  session_summary: "One-sentence rolling summary of entire session so far",
  nodes: [...]
})
```

- `turn_summary` — per-turn, goes to blob chunk metadata (not a fact)
- `session_summary` — rolling, goes to searchable fact (short, 1 sentence)

Files: `doc/memory-prompt.md`, `~/.claude/CLAUDE.md`

---

## Files to modify

| File | Change |
|------|--------|
| `resources/schema.edn` | Add `:node/session-id`, `:node.type/session` |
| `src/ai_memory/mcp/session.clj` | **NEW** — turn summary RAM buffer with timestamps |
| `src/ai_memory/mcp/server.clj` | Rewrite `handle-remember`, add `handle-session-sync` |
| `src/ai_memory/mcp/main.clj` | Start HTTP server alongside stdio |
| `src/ai_memory/mcp/protocol.clj` | Add `session_summary` to remember schema + convert-params |
| `src/ai_memory/web/api.clj` | Add `session-sync` handler |
| `src/ai_memory/web/handler.clj` | Add `/api/session/sync` route |
| `doc/memory-prompt.md` | Update memory_remember example + descriptions |
| `~/.claude/CLAUDE.md` | Sync from doc/memory-prompt.md |
| `~/.claude/hooks/session-sync.bb` | **NEW** — Babashka hook script (local JSONL parsing, delta sync, UUID tracking) |
| `~/.claude/hooks/state/` | **NEW** — directory for per-session sync state files |
| `~/.claude/settings.json` | Add `hooks.Stop` config |
| `TODO.md` | Add session storage tasks |

## Verification

1. `clj -M:test` — all existing tests pass
2. Start MCP server (`clj -M:mcp`) — verify HTTP also starts (curl /api/health)
3. Test `memory_remember` with turn_summary + session_summary — verify:
   - Facts stored in Datomic
   - Turn summary in RAM buffer with timestamp (not as fact)
   - Session fact created/updated
4. Test hook script with mock JSONL — verify:
   - Parses JSONL, extracts only user/assistant messages
   - Tracks last_uuid in state file
   - Sends only delta on second invocation
   - Handles missing last_uuid gracefully (sends all)
5. Test `/api/session/sync` with mock messages — verify:
   - Blob created with sections
   - Turn summaries matched to sections by timestamp
   - Section without matching summary gets `nil` summary
   - Blob meta has detailed summary (joined section summaries)
   - Session fact linked to blob
6. End-to-end: new Claude Code session → work → verify:
   - blob appears in `data/blobs/` with per-turn sections
   - each section has summary from memory_remember
   - meta.edn browsable for lazy loading
