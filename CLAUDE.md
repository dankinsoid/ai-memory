# ai-memory

Long-term memory system for AI agents (MCP server). Clojure + Datomic + Qdrant.

Early development — no backwards compatibility concerns, no production DB yet.

## Plugin (client-side)

`plugins/ai-memory/` — Claude Code native plugin. Contains hooks, skills, agents, MCP config, and CLAUDE.md instructions.

```bash
# Install (once): register private repo as marketplace, then install
/plugin marketplace add git@github.com:dankinsoid/ai-memory.git
/plugin install ai-memory

# Dev mode (single session, no install)
claude --plugin-dir ./plugins/ai-memory
```

Requires `AI_MEMORY_TOKEN` env var for MCP server auth.

Edit files in `plugins/ai-memory/` — changes take effect after plugin update.

Legacy `claude_config_files/` and `scripts/deploy.bb` are deprecated.

## Commands

Requires a backend alias (`:datomic` or `:datalevin`):

```bash
clj -M:datomic:dev          # REPL (nREPL + CIDER) with Datomic backend
clj -M:datomic:run          # Run service (port 8080)
clj -M:datomic:test         # Tests (kaocha)
```

Local dev: run service directly, no Docker. Restart after code changes.

## Server (DigitalOcean)

IP: `46.101.153.18`, user: `root`, SSH key: `~/.ssh/digital_ocean`

```bash
# SSH
ssh -i ~/.ssh/digital_ocean root@46.101.153.18

# lazydocker (interactive TUI)
ssh -i ~/.ssh/digital_ocean root@46.101.153.18 -t 'cd /opt/ai-memory && lazydocker'

# Docker compose (on server)
cd /opt/ai-memory
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs -f app
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d

# Grafana/Prometheus (behind firewall, access via SSH tunnel)
ssh -i ~/.ssh/digital_ocean -L 3000:localhost:3000 -L 9090:localhost:9090 root@46.101.153.18
# then open http://localhost:3000 (Grafana) or http://localhost:9090 (Prometheus)

# Health check
curl http://46.101.153.18:8080/api/health

# First-time server init
ssh root@IP 'bash -s' < scripts/server-init.sh
```

CI: push to `main` → GitHub Actions auto-deploys via SSH. Secrets: `DO_HOST`, `DO_USER`, `DO_SSH_KEY`.

Blobs are located at `/var/lib/docker/volumes/ai-memory_blob-data/_data` on the host.

## Key ADRs

`doc/adr/` — read before architectural changes. Key: ADR-009 (tag retrieval), ADR-010 (blob storage).

## Commit Rules

Before each commit to `main`, bump the plugin version in `plugins/ai-memory/.claude-plugin/plugin.json` (patch version unless the change warrants minor/major).

## TODO Tracking

`TODO.md` — актуальный чеклист реализации проекта. При любых изменениях (новая фича, завершение задачи, смена статуса) — обновлять `TODO.md` в том же коммите.
