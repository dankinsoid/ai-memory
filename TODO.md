# TODO — Datalevin Support

## Phase 1: Decouple common code from Datomic/Qdrant
- [x] Remove direct `datomic.api` calls from common src (tag/core.clj, graph/node.clj)
- [x] Rename `:qdrant` → `:vector-store` in health/diagnostics API responses
- [x] Clean Datomic/Qdrant references from docstrings in common code

## Phase 2: Split source paths
- [x] Create `src-datomic/` directory structure
- [x] Move datomic-specific files:
  - `db/core.clj` → `src-datomic/`
  - `store/datomic_store.clj` → `src-datomic/`
  - `graph/edge.clj` → `src-datomic/`
  - `graph/traverse.clj` → `src-datomic/`
  - Note: `qdrant_store.clj` and `embedding/vector_store.clj` stay in `src/` (no datomic dep, reusable by any backend)
- [x] Split `system.clj`:
  - Common init-keys stay in `src/ai_memory/system.clj` (config, metrics, embedding, context, web, scheduler)
  - Backend-specific init-keys → `src-datomic/ai_memory/system/backend.clj` (:db/conn, :store/fact, :store/vectors, :store/tag-vectors)
  - `system.clj` requires `ai-memory.system.backend` (whichever is on classpath)
- [x] Fix tests: replace `tag/ensure-tag!` calls with `p/ensure-tag!` on FactStore protocol
- [x] Rewrite `tag/core_test.clj`: test tag vectorization via `service.tags/ensure!` instead of impl-specific ensure-tag

## Phase 3: Update deps.edn & decouple tests
- [x] Move `com.datomic/peer` to `:datomic` alias with `extra-paths ["src-datomic"]`
- [x] Create `:datalevin` alias with `datalevin/datalevin` dep and `extra-paths ["src-datalevin"]`
- [x] Commands now require backend alias: `clj -M:datomic:run`, `clj -M:datomic:test`
- [x] Remove store-level tests (tag/core_test, tag/query_test, graph/write_test)
- [x] Strip Datomic fixture from mcp/protocol_test — keep only pure rendering/protocol tests
- [x] Verify: `clj -M:test` compiles without datomic (55 tests pass)
- [ ] Add integration tests with Integrant-based fixtures (backend-agnostic, uses system.edn)

## Phase 4: Datalevin implementation
- [ ] `src-datalevin/ai_memory/db/datalevin.clj` — connection, schema, tick counter
- [ ] `src-datalevin/ai_memory/store/datalevin_store.clj` — FactStore protocol implementation
  - Datalevin Datalog queries (similar but not identical to Datomic)
  - Built-in vector search for VectorStore protocol (KNN)
- [ ] `src-datalevin/ai_memory/system/backend.clj` — Integrant init-keys for Datalevin
- [ ] Schema migration: translate `schema.edn` from Datomic format to Datalevin format
- [ ] Verify: `clj -M:datalevin:run`, `clj -M:datalevin:test`

## Phase 5: Validation
- [ ] All existing tests pass with `:datomic` alias
- [ ] Core tests pass with `:datalevin` alias
- [ ] Health check works with both backends
- [ ] Manual smoke test: remember → recall cycle

## Open Questions
- Datalevin vector search: does built-in KNN quality match Qdrant for our use case?
- Datalevin schema: any Datomic features we rely on that Datalevin doesn't support?
- Data migration: do we need a tool to migrate existing Datomic data to Datalevin?
- Docker: Datalevin is embedded — simplifies deployment (no Qdrant/Datomic containers)
