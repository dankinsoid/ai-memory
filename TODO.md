# TODO

## Core Infrastructure
- [x] System startup, entry point (`core.clj`)
- [x] Configuration from env vars (`config.clj`)
- [x] Datomic connection, schema, tick counter (`db/core.clj`)
- [x] Datomic schema + seed tags (`schema.edn`, `seed-tags.edn`)
- [x] Prometheus metrics (`metrics.clj`)

## Tag Taxonomy (ADR-009 — primary retrieval)
- [x] Tag CRUD: create, ensure, find by path/name (`tag/core.clj`)
- [x] Taxonomy navigation: root, children, subtree, full tree
- [x] Tag-based queries: by-tag, intersection, union, subtree (`tag/query.clj`)
- [x] Browse with node counts
- [x] Tag auto-create on write path (`tag/core.clj` — `ensure-tag!` called inline, `tag/resolve.clj` deleted)
- [x] Tests (20 tests)

### Agent Retrieval Flow (doc/agent-tag-flow.md)
- [x] `:tag/node-count` materialized counter recomputed at startup (`db/core.clj` — `recompute-tag-counts!`)
- [x] `:node/tags` flat string set on nodes (`db.type/string, cardinality/many, indexed`) — replaced `:node/tag-refs` (ref)
- [x] `taxonomy [db path max-depth]` — depth-limited tree with counts per node
- [x] `count-by-tag-sets [db tag-sets]` — `[[tags] ...] → [{:tags :count} ...]` without pulling nodes
- [x] `fetch-by-tag-sets [db tag-sets opts]` — batch `by-tags` with `:limit`
- [x] `browse` uses materialized counts (O(1) per tag)
- [x] Read query metrics: `read-duration`, `read-total` (`metrics.clj`)
- [x] MCP handlers: `handle-browse-tags`, `handle-count-facts`, `handle-get-facts`
- [x] REST routes: `POST /api/tags/count`, `POST /api/tags/facts`, `GET /api/tags?depth=N`
- [x] `reconcile-counts!` — recomputes all counts from actual data (`tag/query.clj`)
- [x] Scheduler: daily reconciliation at 03:00 (`scheduler.clj`)
- [x] Tests (16 tests, 36 assertions)

## Graph — Write Pipeline
- [x] Node CRUD + vectorization (`graph/node.clj`)
- [x] Edge CRUD + strengthen (`graph/edge.clj`)
- [x] Write pipeline: batch, context, global edges (`graph/write.clj`)
- [x] Deduplication: exact (entities), vector similarity (facts)
- [x] Reinforcement on duplicate detection
- [x] Context cache with TTL
- [x] Tests (20+ tests)

## Graph — Read Pipeline (paused)
- [x] Spreading activation code (`graph/traverse.clj`) — written, not in use
- [x] Weight decay model (`decay/core.clj`) — written, not in use
- [x] `memory_reinforce` MCP tool — explicit fact weight feedback (-1 to 1)
- [ ] Edge reinforcement via `memory_reinforce` — strengthen/weaken associations when fact reached via edges
- [ ] Activate graph-based retrieval once enough edge data accumulates
- [ ] Weight/decay strategy: нет выработанной стратегии когда и как агент должен reinforcement-ить факты, какой обучающий сигнал реально полезен, стоит ли показывать веса агенту (сейчас убраны из session-start)

### Graph API & MCP
- [ ] API endpoint: get related facts for a given fact (follow edges, return neighbors with weights)
- [ ] MCP tool: `memory_related` — fetch facts connected by edges (1-hop, configurable depth)
- [ ] Edge strengthen/weaken API — explicit feedback on edge usefulness (not just node reinforcement)

### Graph Traversal & Inference
- [ ] Multi-hop traversal: follow chains of edges to discover indirect connections
- [ ] Intersection discovery: facts reachable from multiple starting points (convergence = signal)
- [ ] Spreading activation with configurable decay per hop — surface non-obvious patterns
- [ ] Cluster detection: find tightly connected subgraphs (implicit topics/themes)

### Recall-Based Refreshing (memory reconsolidation)
- [ ] When fact retrieved via implicit path (graph traversal, not direct tag/search) — reset decay timestamp
- [ ] Slight weight boost on implicit retrieval (simulates "remembering refreshes the memory")
- [ ] Track retrieval source (direct vs graph-inferred) for analytics

## Embedding & Vector Search
- [x] TEI client: embed, embed-batch (`embedding/core.clj`)
- [x] Qdrant client: upsert, search, delete (`embedding/vector_store.clj`)

## Web API
- [x] REST routes: health, remember, recall, tags, nodes, graph (`web/handler.clj`)
- [x] API handlers with JSON serialization (`web/api.clj`)
- [x] `GET /api/stats` — global counts (facts, tags, edges, tick)
- [x] `GET /api/graph/top-nodes` — highest effective-weight nodes for graph entry
- [x] `GET /api/graph/neighborhood` — BFS subgraph around a node (lazy graph loading)
- [x] `GET /api/facts/:id` — single fact detail with edges and metadata
- [x] `offset` + `total` pagination in `/api/tags/facts` filters
- [ ] Integration tests for Web API

## MCP Server (agent interface)
- [x] Handler functions: browse-tags, count-facts, get-facts, remember, create-tag (`mcp/server.clj`)
- [x] MCP wire protocol: JSON-RPC 2.0 over stdio (`mcp/transport.clj`, `mcp/protocol.clj`, `mcp/main.clj`)
- [x] Tool registry: 9 tools with JSON Schema input specs
- [x] Entry point: `clj -M:mcp` (stderr-only logging via `logback-mcp.xml`)
- [x] Protocol tests (12 tests)
- [x] Register as MCP server in `~/.claude/settings.json` (in-memory Datomic, `clj -M:mcp`)
- [x] Memory usage instructions in `CLAUDE.md`
- [x] `project_path` parameter in `memory_store_conversation` tool
- [ ] End-to-end test: agent → MCP → remember → recall

## Blob Storage (ADR-010)
- [x] Blob = Node model: `:node.type/conversation`, `:node.type/document` with `:node/blob-dir`
- [x] Schema: `:node/created-at`, `:node/updated-at` (indexed), `:node/blob-dir`, `:node/sources` (many)
- [x] Auto-timestamps: `create-node` sets both, `reinforce-node`/`update-tags` bump `updated-at`
- [x] Directory structure: `data/blobs/{YYYY-MM-DD}_{slug}/meta.edn` + section files
- [x] Filesystem ops: write/read meta.edn and sections (`blob/store.clj`)
- [x] Lazy access: meta first, section by index
- [x] Fact → blob linking via `:node/sources` (set of `"dir/file"` strings)
- [x] MCP tools: `memory_list_blobs`, `memory_read_blob`, `memory_store_conversation`, `memory_store_file`
- [x] Compact text renderers for blob list and meta
- [x] Source indicator in fact rendering (`[src: ...]`)
- [x] Tests (12 tests, 25 assertions)
- [x] `memory_read_blob` MCP tool — execute bash in blob dir via ProcessBuilder
- [x] Removed SSHFS preflight hook and direct blob path exposure

## Git Integration
- [x] Auto-capture git context (branch, start-commit, end-commit, remote) in session blob meta.edn (`web/api.clj`)
- [ ] Show git branch in session context (MEMORY.md Recent Sessions section)
- [ ] Stable project ID via git root commit hash (`git rev-list --max-parents=0 HEAD`)
- [ ] Fallback to folder name when no git
- [ ] Handle project renames: map old folder name → stable ID

## Session Ingestion
- [x] Read `~/.claude/projects/.../*.jsonl` (`blob/ingest.clj`)
- [x] Parse and clean user/assistant text messages
- [x] Strip system-reminder, ide, and hook tags
- [x] Split by agent-guided boundaries or auto-split
- [x] Format as clean markdown sections
- [ ] End-to-end test: store-conversation with real session JSONL

## Frontend (Playground v2 — Preact + Sigma.js, no build step)
- [x] Replaced ClojureScript/Reagent/D3 with plain JS + CDN (Preact, HTM, Sigma.js)
- [x] Design system: dark theme with depth layering, CSS custom properties (`css/theme.css`)
- [x] Explore view: tag sidebar with multi-select, semantic search, infinite-scroll fact list
- [x] Fact detail panel: slide-in with full metadata, edges, blob/session info
- [x] Graph view: Sigma.js WebGL graph, lazy neighborhood expansion, ForceAtlas2 layout
- [x] Stat bar: live fact/tag/edge/tick counters
- [x] Keyboard shortcuts: `/` to search, `Escape` to close
- [ ] Polish: skeleton loading states, responsive layout, empty state illustrations

## Infrastructure
- [x] Docker Compose: Datomic, app, TEI, Qdrant, Prometheus, Grafana
- [x] Dockerfile (multi-stage build)
- [x] Datomic Pro transactor Docker setup
- [x] Prometheus config
- [x] Grafana dashboards: Overview, Write Pipeline, Read Pipeline (provisioned via JSON)

## Auto-Retrieval (mid-conversation context injection)
- [ ] Tag-based injection hook: extract technical terms from user message (rule-based, no LLM), query `memory_get_facts` by tags, inject top-5 facts as system-reminder
- [ ] Tag extractor: match known tags against message text using substring/regex (cheap, deterministic)
- [ ] Project-scoped filtering: current project tag + `universal`
- [ ] Use semantic search (Qdrant) as complementary filter when tag extraction yields few hits

## Stochastic Save Reminder
- [x] New hook `memory-nudge.bb`: fires with ~30% probability on UserPromptSubmit
- [x] Short reminder to agent: "did this exchange surprise you or change your understanding?"
- [x] Does NOT ask to review whole session — prevents predictable batch saves
- [x] Register in `settings.json` alongside existing `session-reminder.bb`

## Memory Toggle Control (coexistence with Claude's built-in auto-memory)
- [x] `undeploy.bb` — removes prompt section + hooks from `~/.claude/` (full off switch)
- [x] `AI_MEMORY_DISABLED=1` — master env var: all hooks exit early
- [x] `AI_MEMORY_NO_READ=1` — session-start injects no context
- [x] `AI_MEMORY_NO_WRITE=1` — session-sync/end/reminder/nudge skip writes
- [x] `AI_MEMORY_NO_SESSIONS=1` — disable session tracking and session context injection
- [x] `AI_MEMORY_NO_FACTS=1` — disable fact nudges and fact context injection
- [x] `prompt.md` reframed as "External Memory (ai-memory)" to avoid conflict with built-in memory

## memory-scribe Sub-Agent
- [x] Create `claude_config_files/agents/memory-scribe.md` — receives raw candidate observation + brief context
- [x] Agent applies 4-filter algorithm: future-agent test, code test, generalization test, moment-of-insight test
- [x] Agent formats: sentence ≤15 words, lowercase, imperative if actionable
- [x] Agent picks tags: aspect first (pitfall/preference/decision/insight/pattern), then project, then technical
- [x] Agent calls `memory_remember`, returns what was saved (or "skipped: reason")
- [x] Simplify main agent prompt: "when you notice something non-obvious → pass candidate to memory-scribe"
- [x] Add `agents/` to `deploy.bb` copy targets alongside `skills/` and `scripts/`

## Prompt Engineering (prompt.md)
- [x] Reframe "fact" → "note": rewrite intro as "what's worth telling your future self"
- [x] Replace "When to Remember" triggers with 4-filter decision algorithm (future-agent / code / generalization / moment-of-insight tests)
- [x] Add save timing rule: "save at moment of insight, not at session end — one note per observation"
- [x] Replace "Abstraction Levels" (concrete/pattern/meta) with explicit aspect tag vocabulary: pitfall, preference, decision, insight, pattern — with one example each
- [x] Strengthen project-tag: make it step 0 before any tagging, add bad example (project-specific note without project tag)
- [x] Expand "Mid-Session Retrieval" triggers: add "before starting a significant subtask" and "when encountering an error" to cover autonomous tool-call chains

## Dev & Build
- [x] REPL helpers: start/stop/restart (`dev/user.clj`)
- [x] deps.edn: dev, test, build aliases
- [x] Kaocha test runner
- [x] Uberjar build (`build.clj`)
