# ai-memory

Long-term memory system for AI agents. Designed for years of use across many projects, making agents more personalized and capable of learning.

## Quick Start

### Prerequisites

- [Clojure CLI](https://clojure.org/guides/install_clojure)
- [Babashka](https://babashka.org/) (`bb`) — for hook scripts
- Docker (for Datomic)

### 1. Start the server

```bash
docker compose up -d   # Datomic
clj -M:run             # ai-memory server on :8080
```

### 2. Register MCP server

Add to `~/.config/claude/.mcp.json`:

```json
{
  "mcpServers": {
    "ai-memory": {
      "command": "clj",
      "args": ["-M:mcp"],
      "cwd": "/path/to/ai-memory",
      "env": {
        "AI_MEMORY_URL": "http://localhost:8080"
      }
    }
  }
}
```

### 3. Deploy agent config

```bash
bb scripts/deploy.bb
```

This copies config from `claude_config_files/` to `~/.claude/`:

| Source | Destination | Strategy |
|--------|-------------|----------|
| `prompt.md` | `~/.claude/CLAUDE.md` | Replace section between `<!-- ai-memory:start/end -->` markers |
| `settings.json` | `~/.claude/settings.json` | Merge (no duplicate permissions or hooks) |
| `scripts/*.bb` | `~/.claude/hooks/` | Overwrite |
| `skills/*/` | `~/.claude/skills/*/` | Overwrite matching, leave others |

Idempotent — run again after any changes.

## Agent Config (`claude_config_files/`)

- **prompt.md** — memory system instructions injected into global `CLAUDE.md`
- **settings.json** — auto-allow 10 `mcp__ai-memory__*` tools + hooks:
  - `SessionStart` → `memory-preflight.bb` (SSHFS mount for remote blobs)
  - `Stop` → `session-sync.bb` (sync transcript to server)
- **skills/** — `load/` (recover previous session), `save/` (save session to memory)

## Development

```bash
clj -M:dev          # REPL (nREPL + CIDER)
clj -M:test         # Run tests (kaocha)
clj -M:test --focus ai-memory.some-test  # Single namespace
```

In REPL: `(start)`, `(stop)`, `(restart)` from `dev/user.clj`.

## Architecture

Two-layer storage: **facts** (Datomic, ~200 bytes each) and **blobs** (files, 1-500 KB). Facts carry flat atomic tags — primary retrieval via tag intersection. See `doc/adr/` for design decisions.
