# ai-memory

Long-term memory system for AI agents (MCP server). Clojure + Datomic + Qdrant.

Early development — no backwards compatibility concerns, no production DB yet.

## Source of Truth

`claude_config_files/` → manually copied to `~/.claude/`:
- `prompt.md` → global CLAUDE.md (Memory section)
- `settings.json`, `skills/`, `scripts/` → corresponding dirs

NEVER edit `~/.claude/` files directly - use `bb scripts/deploy.bb` to copy from `claude_config_files/` to `~/.claude/`.

Always edit in this repo first, then copy.

## Commands

```bash
clj -M:dev          # REPL (nREPL + CIDER)
clj -M:run          # Run service (port 8080)
clj -M:test         # Tests (kaocha)
bb scripts/deploy.bb  # Deploy
```

Local dev: run service directly (`clj -M:run`), no Docker. Restart after code changes.

## Key ADRs

`doc/adr/` — read before architectural changes. Key: ADR-009 (tag retrieval), ADR-010 (blob storage).

## TODO Tracking

`TODO.md` — актуальный чеклист реализации проекта. При любых изменениях (новая фича, завершение задачи, смена статуса) — обновлять `TODO.md` в том же коммите.
