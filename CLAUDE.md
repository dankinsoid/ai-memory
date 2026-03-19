# ai-memory

Long-term memory system for AI agents (MCP server).

Early development — no backwards compatibility concerns, no production DB yet.

## Plugin

`plugins/ai-memory/` — Claude Code native plugin. Contains hooks, skills, agents, MCP config, and CLAUDE.md instructions.

```bash
# Install (once): register private repo as marketplace, then install
/plugin marketplace add git@github.com:dankinsoid/ai-memory.git
/plugin install ai-memory

# Dev mode (single session, no install)
claude --plugin-dir ./plugins/ai-memory
```

Edit files in `plugins/ai-memory/` — changes take effect after plugin update.

Legacy `claude_config_files/` and `scripts/deploy.bb` are deprecated.

## Commit Rules

Before each commit to `main`, bump the plugin version in `plugins/ai-memory/.claude-plugin/plugin.json` (patch version unless the change warrants minor/major).

## TODO Tracking

`TODO.md` — актуальный чеклист реализации проекта. При любых изменениях (новая фича, завершение задачи, смена статуса) — обновлять `TODO.md` в том же коммите.
