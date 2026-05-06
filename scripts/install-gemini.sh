#!/usr/bin/env bash
# @ai-generated(solo)
# Install ai-memory plugin for Gemini CLI.
#
# Configures MCP server and merges hooks into ~/.gemini/settings.json.
# Idempotent — safe to re-run after updates.
#
# Usage:
#   bash scripts/install-gemini.sh
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Path resolution
# ---------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PLUGIN_ROOT="$REPO_ROOT/plugins/ai-memory"
GEMINI_DIR="$HOME/.gemini"

# ---------------------------------------------------------------------------
# Preflight checks
# ---------------------------------------------------------------------------

if [ ! -d "$GEMINI_DIR" ]; then
    echo "Error: ~/.gemini/ not found. Install Gemini CLI first." >&2
    exit 1
fi

if [ ! -f "$PLUGIN_ROOT/mcp/server.py" ]; then
    echo "Error: $PLUGIN_ROOT/mcp/server.py not found." >&2
    exit 1
fi

if ! command -v python3 &>/dev/null; then
    echo "Error: python3 not found." >&2
    exit 1
fi

PY_VERSION=$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')
PY_MAJOR=$(echo "$PY_VERSION" | cut -d. -f1)
PY_MINOR=$(echo "$PY_VERSION" | cut -d. -f2)
if [ "$PY_MAJOR" -lt 3 ] || { [ "$PY_MAJOR" -eq 3 ] && [ "$PY_MINOR" -lt 11 ]; }; then
    echo "Error: Python 3.11+ required (found $PY_VERSION)." >&2
    exit 1
fi

echo "Installing ai-memory for Gemini CLI..."
echo "  Plugin: $PLUGIN_ROOT"
echo ""

# ---------------------------------------------------------------------------
# Merge MCP server + hooks into ~/.gemini/settings.json
#
# Gemini CLI uses a single settings.json for both MCP servers and hooks,
# unlike Codex which splits them into config.toml + hooks.json.
# ---------------------------------------------------------------------------

GEMINI_SETTINGS="$GEMINI_DIR/settings.json"
PLUGIN_HOOKS="$PLUGIN_ROOT/.gemini/settings.json"

if [ -f "$GEMINI_SETTINGS" ]; then
    cp "$GEMINI_SETTINGS" "$GEMINI_SETTINGS.bak.$(date +%s)"
fi

python3 -c "
import copy
import json
import os
import re
import shlex

user_path = '$GEMINI_SETTINGS'
plugin_path = '$PLUGIN_HOOKS'
plugin_root = '$PLUGIN_ROOT'
server_path = '$PLUGIN_ROOT/mcp/server.py'

# Load plugin hooks template
with open(plugin_path) as f:
    plugin_data = json.load(f)

# Load existing user settings or start fresh
try:
    with open(user_path) as f:
        user_data = json.load(f)
except (FileNotFoundError, json.JSONDecodeError):
    user_data = {}

# Read AI_MEMORY_* and OPENAI_API_KEY from Claude settings for env baking
hook_env = {}
mcp_env = {}
try:
    with open(os.path.expanduser('~/.claude/settings.json')) as f:
        claude_env = json.load(f).get('env', {})
    for key, value in claude_env.items():
        if key.startswith('AI_MEMORY_') or key == 'OPENAI_API_KEY':
            if isinstance(value, bool):
                value = 'true' if value else 'false'
            hook_env[key] = str(value)
            mcp_env[key] = str(value)
except Exception:
    pass


def rewrite_command(command: str) -> str:
    \"\"\"Replace \$AI_MEMORY_PLUGIN_ROOT with absolute path and bake env vars.\"\"\"
    env_prefix = ' '.join(
        f'{key}={shlex.quote(value)}' for key, value in sorted(hook_env.items())
    )

    def repl(match):
        suffix = match.group(1)
        return shlex.quote(f'{plugin_root}/{suffix}')

    command = re.sub(r'\\\$AI_MEMORY_PLUGIN_ROOT/([^\s\"\']+)', repl, command)
    return f'{env_prefix} {command}' if env_prefix else command


# ---- Merge hooks ----
if 'hooks' not in user_data:
    user_data['hooks'] = {}

for event_type, plugin_entries in plugin_data.get('hooks', {}).items():
    existing = user_data['hooks'].get(event_type, [])

    # Remove stale ai-memory entries before re-adding
    existing = [
        entry for entry in existing
        if not any(
            (
                'AI_MEMORY_PLUGIN_ROOT' in h.get('command', '')
                or plugin_root in h.get('command', '')
            )
            for h in entry.get('hooks', [])
        )
    ]

    rendered_entries = []
    for entry in plugin_entries:
        rendered = copy.deepcopy(entry)
        for hook in rendered.get('hooks', []):
            command = hook.get('command')
            if command:
                hook['command'] = rewrite_command(command)
        rendered_entries.append(rendered)

    existing.extend(rendered_entries)
    user_data['hooks'][event_type] = existing

# ---- Merge MCP server ----
if 'mcpServers' not in user_data:
    user_data['mcpServers'] = {}

# Build MCP entry with absolute path and baked env vars
mcp_entry = {
    'command': 'python3',
    'args': [server_path],
}
if mcp_env:
    mcp_entry['env'] = mcp_env

user_data['mcpServers']['ai-memory'] = mcp_entry

with open(user_path, 'w') as f:
    json.dump(user_data, f, indent=2)
    f.write('\n')
"

echo "  ✓ MCP server configured in settings.json"
echo "  ✓ Hooks merged into settings.json"
echo "  ✓ Hook commands use absolute paths and baked-in AI_MEMORY_* env"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
echo "Done! Restart Gemini CLI if it was already running."
echo ""
echo "Configured:"
echo "  MCP server:  $PLUGIN_ROOT/mcp/server.py"
echo "  Settings:    $GEMINI_SETTINGS"
echo "  Hook paths:  baked into settings.json"
echo ""
echo "Known limitations:"
echo "  - No SessionEnd event: final digest (session-final-digest.py) won't run."
echo "  - Session syncs happen incrementally via AfterAgent only."
