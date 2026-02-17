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
- [x] Tag resolution: string → ref, auto-create missing (`tag/resolve.clj`)
- [x] Tests (20 tests)

### Agent Retrieval Flow (doc/agent-tag-flow.md)
- [x] `:tag/node-count` materialized counter + atomic tx function (`schema.edn`, `db/core.clj`)
- [x] Count maintenance in write pipeline (`graph/node.clj` — create + update)
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
- [ ] Activate graph-based retrieval once enough edge data accumulates

## Embedding & Vector Search
- [x] TEI client: embed, embed-batch (`embedding/core.clj`)
- [x] Qdrant client: upsert, search, delete (`embedding/vector_store.clj`)

## Web API
- [x] REST routes: health, remember, recall, tags, nodes, graph (`web/handler.clj`)
- [x] API handlers with JSON serialization (`web/api.clj`)
- [ ] Integration tests for Web API

## MCP Server (agent interface)
- [x] Handler functions: browse-tags, count-facts, get-facts, remember, create-tag (`mcp/server.clj`)
- [x] MCP wire protocol: JSON-RPC 2.0 over stdio (`mcp/transport.clj`, `mcp/protocol.clj`, `mcp/main.clj`)
- [x] Tool registry: 5 tools with JSON Schema input specs
- [x] Entry point: `clj -M:mcp` (stderr-only logging via `logback-mcp.xml`)
- [x] Protocol tests (12 tests)
- [ ] Register as MCP server in `~/.claude/settings`
- [ ] End-to-end test: agent → MCP → remember → recall

## Blob Storage (ADR-010)
- [ ] Blob directory structure (`data/blobs/{id}/meta.edn` + `chunk-*.jsonl`)
- [ ] Write: chunk large content, write meta + chunks
- [ ] Read: meta first, lazy chunk loading
- [ ] Link facts → blobs via `:memory/source`

## Session Ingestion
- [ ] Read `~/.claude/projects/.../*.jsonl`
- [ ] Parse and clean user/assistant messages
- [ ] Store as blob chunks
- [ ] Extract facts from session context

## Frontend
- [x] D3 force-directed graph visualization (`ui/graph.cljs`)
- [x] Tab navigation: Graph / Tags (`ui/core.cljs`)
- [x] HTTP fetch utility (`ui/http.cljs`)
- [x] Tag taxonomy browser — D3 collapsible tree, multi-select, facts panel (`ui/tags.cljs`)
- [ ] Search / filter interface
- [ ] Node detail view

## Infrastructure
- [x] Docker Compose: Datomic, app, TEI, Qdrant, Prometheus, Grafana
- [x] Dockerfile (multi-stage build)
- [x] Datomic Pro transactor Docker setup
- [x] Prometheus config
- [ ] Grafana dashboards (provisioning configured, no dashboards)

## Dev & Build
- [x] REPL helpers: start/stop/restart (`dev/user.clj`)
- [x] deps.edn: dev, test, cljs, build aliases
- [x] Kaocha test runner
- [x] Shadow-cljs config
- [x] Uberjar build (`build.clj`)
