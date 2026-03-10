# TODO — Datalevin Support

## Phase 1: Decouple common code from Datomic/Qdrant
- [x] Remove direct `datomic.api` calls from common src (tag/core.clj, graph/node.clj)
- [x] Rename `:qdrant` → `:vector-store` in health/diagnostics API responses
- [x] Clean Datomic/Qdrant references from docstrings in common code

## Phase 2: Split source paths
- [ ] Create `src-datomic/` directory structure
- [ ] Move datomic-specific files:
  - `db/core.clj` → `src-datomic/`
  - `store/datomic_store.clj` → `src-datomic/`
  - `store/qdrant_store.clj` → `src-datomic/`
  - `embedding/vector_store.clj` → `src-datomic/` (Qdrant HTTP client)
  - `graph/edge.clj` → `src-datomic/` (PAUSED, direct datomic.api)
  - `graph/traverse.clj` → `src-datomic/` (PAUSED, depends on edge.clj)
- [ ] Split `system.clj`:
  - Common init-keys stay in `src/ai_memory/system.clj` (config, metrics, embedding, context, web, scheduler)
  - Backend-specific init-keys → `src-datomic/ai_memory/system/backend.clj` (:db/conn, :store/fact, :store/vectors)
  - `system.clj` requires `ai-memory.system.backend` (whichever is on classpath)

## Phase 3: Update deps.edn
- [ ] Move `com.datomic/peer` and `clj-http` to `:datomic` alias with `extra-paths ["src-datomic"]`
- [ ] Create `:datalevin` alias with `datalevin/datalevin` dep and `extra-paths ["src-datalevin"]`
- [ ] Update `:run`, `:dev`, `:test` aliases to compose with backend alias
- [ ] Verify: `clj -M:datomic:run`, `clj -M:datomic:test`

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
