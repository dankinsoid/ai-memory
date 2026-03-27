# ai-memory

Long-term memory plugin for [Claude Code](https://docs.anthropic.com/en/docs/claude-code). Sessions, rules, and facts that persist across conversations.

## Core Ideas

**Sessions** — the agent automatically saves session transcripts and can write compact summaries (goals, decisions, blockers, dead ends). A new session can load a previous one and continue where it left off, with full context.

**Session chaining via `/clear`** — `/clear` is not just a reset. The plugin detects the outgoing session, loads its summary/compact into the next session, and links them. This works as an alternative to the native compact: instead of compressing a bloated context, you start fresh with a clean window while keeping the essential context from the previous session.

**Lazy rules** (in development) — rules and conventions are not dumped into context all at once. They're loaded dynamically: relevant rules are prefetched based on what you're discussing and injected right before the agent acts. Plan-relevant rules are loaded after planning, before execution.

**Facts & knowledge** — not limited to sessions and rules. You can ask the agent to remember anything — debugging insights, API quirks, deployment notes, personal preferences — and search for it later.

**Plain Markdown** — everything is stored as `.md` files with YAML front-matter. No background process, no database server. You can read, edit, and organize your memory with any text editor, commit it to git, or share across machines. Files are [Obsidian](https://obsidian.md)-compatible — session transcripts use callout blocks, and cross-references use `[[wikilinks]]`.

## Install

Requires: Python 3.10+, Claude Code with plugin support.

```bash
# Register the repository as a plugin marketplace (once)
/plugin marketplace add git@github.com:dankinsoid/ai-memory.git

# Install the plugin
/plugin install ai-memory
```

For development:

```bash
claude --plugin-dir ./plugins/ai-memory
```

### Optional: Semantic search

OpenAI features are opt-in to avoid silently spending tokens when `OPENAI_API_KEY` is set globally. Add to your Claude Code `settings.json`:

```json
{
  "env": {
    "OPENAI_API_KEY": "sk-...",
    "AI_MEMORY_EMBEDDING": "true",
    "AI_MEMORY_EMBEDDING_MODEL": "text-embedding-3-small",
    "AI_MEMORY_LLM": "true",
    "AI_MEMORY_LLM_MODEL": "gpt-4.1-nano",
    "AI_MEMORY_LLM_PROVIDER": "openai"
  }
}
```

| Variable | Default | Description |
|----------|---------|-------------|
| `AI_MEMORY_EMBEDDING` | `false` | Enable vector embeddings |
| `AI_MEMORY_EMBEDDING_MODEL` | `text-embedding-3-small` | OpenAI embedding model |
| `AI_MEMORY_LLM` | `false` | Enable LLM calls (future) |
| `AI_MEMORY_LLM_MODEL` | `gpt-4.1-nano` / `haiku` | Chat model (default depends on provider) |
| `AI_MEMORY_LLM_PROVIDER` | auto | `openai` or `claude-cli`. When omitted, uses `openai` if `OPENAI_API_KEY` is set, otherwise falls back to `claude-cli` |
| `OPENAI_API_KEY` | — | Required for embeddings and `openai` LLM provider. `claude-cli` provider uses CLI auth — no key needed |

Without these, the plugin works fully via tag-based filtering. The `claude-cli` provider requires no API key — it uses your existing Claude Code CLI authentication.

By default, embeddings are stored in the local SQLite cache. To use an external [Qdrant](https://qdrant.tech) instance instead:

```bash
export QDRANT_URL="http://localhost:6333"
export QDRANT_API_KEY="..."  # optional, for Qdrant Cloud
```

### Optional: Obsidian integration

Set `AI_MEMORY_DIR` to point at a folder inside your Obsidian vault:

```bash
export AI_MEMORY_DIR="$HOME/ObsidianVault/ai-memory"
```

A minimal Obsidian plugin for styling session transcripts is included in [`obsidian-chat-view/`](obsidian-chat-view/). Install via [BRAT](https://github.com/TfTHacker/obsidian42-brat) or copy the folder manually into your vault's `.obsidian/plugins/` directory.

## Slash Commands

| Command | What it does |
|---------|-------------|
| `/load` | Load any session and resume. Accepts `last` or free-text search |
| `/rule` | Save a rule, preference, or convention to memory |
| `/save` | Write a session compact — a condensed summary of the entire conversation. Not required for `/load` (transcripts are saved automatically), but useful as a checkpoint when context matters |

## MCP Tools

The plugin runs a local MCP server (stdio, no daemon) with these tools:

| Tool | Purpose |
|------|---------|
| `memory_session` | Create or update a session summary (title, project, tags, compact notes) |
| `memory_remember` | Save a fact or rule with tags and title |
| `memory_search` | Search by tags (AND/OR/exclude), date range, and optional semantic query |
| `memory_read` | Read full content of a memory by `[[wikilink]]` ref |
| `memory_load_session` | Load a previous session for deep recovery |
| `memory_explore_tags` | List all tags with file counts |

All MCP tool calls are auto-approved — no confirmation prompts.

## What Happens Automatically

The plugin uses [Claude Code hooks](https://docs.anthropic.com/en/docs/claude-code/hooks) to manage memory without manual intervention.

### Session lifecycle

**Start** — loads universal rules/facts (up to 5), project-scoped facts and recent sessions (up to 10). On `/clear`, automatically loads the previous session's summary/compact (see session chaining above).

**During** — reminds the agent to save session summaries sometimes. Tracks context token usage and prompts `/save` before the context window fills up (~100K tokens).

**Every turn** — appends the latest conversation chunk to the session `.md` file (async). Records git context (branch, commits) in front-matter.

### Rules loading

Currently, rules are loaded at session start and when saving a session (matching rules are returned alongside the save confirmation). The agent can also search for rules at any time via `memory_search(tags=["rule"])`.

Fully automatic lazy loading (async prefetch based on conversation topics, injection before tool calls and after plan finalization) is in development.

## Storage

Default location: `~/.claude/ai-memory/` (override with `AI_MEMORY_DIR`).

```
ai-memory/
├── universal/
│   ├── rules/            # Apply everywhere
│   └── facts/
├── languages/
│   └── <lang>/           # Language-specific
├── projects/
│   └── <project>/
│       ├── rules/        # Project-specific
│       ├── facts/
│       └── sessions/
└── sessions/
    └── YYYY-MM-DD/       # Date-organized transcripts
```

Each file has YAML front-matter (tags, date, title, etc.). Tags are both explicit and derived from directory structure — a file in `projects/myapp/rules/` automatically gets `project/myapp` and `rule` tags.

### Indexing

A SQLite cache indexes the files for fast tag/date search. Location: `~/Library/Caches/ai-memory/index.db` (macOS) or `~/.cache/ai-memory/index.db` (Linux). The cache is purely derived from the filesystem — delete it anytime, it rebuilds automatically.

### Tag system

Three tiers:

- **Scope**: `universal`, `project/<name>`, `lang/<name>`
- **Aspect**: `testing`, `architecture`, `debugging`, `deployment`, `performance`, `security`, `tooling`, `workflow`, `error-handling`, `api-design`, `concurrency`, `ci-cd`, ...
- **Specific**: any topic tag (`react`, `docker`, `auth`, ...)

Rules (tagged `rule`) get special treatment — loaded at session start and dynamically injected when relevant.

## License

MIT
