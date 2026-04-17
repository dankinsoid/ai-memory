#!/usr/bin/env bash
# @ai-generated(solo)
# Install ai-memory plugin for Codex CLI.
#
# Configures MCP server, merges hooks, and sets environment variables.
# Idempotent — safe to re-run after updates.
#
# Usage:
#   bash scripts/install-codex.sh
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Path resolution
# ---------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PLUGIN_ROOT="$REPO_ROOT/plugins/ai-memory"
CODEX_DIR="$HOME/.codex"

# ---------------------------------------------------------------------------
# Preflight checks
# ---------------------------------------------------------------------------

if [ ! -d "$CODEX_DIR" ]; then
    echo "Error: ~/.codex/ not found. Install Codex CLI first." >&2
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

echo "Installing ai-memory for Codex CLI..."
echo "  Plugin: $PLUGIN_ROOT"
echo ""

# ---------------------------------------------------------------------------
# 1. MCP server in config.toml
# ---------------------------------------------------------------------------

CONFIG_TOML="$CODEX_DIR/config.toml"

if [ -f "$CONFIG_TOML" ]; then
    cp "$CONFIG_TOML" "$CONFIG_TOML.bak.$(date +%s)"
fi

python3 -c "
import re, sys, os

config_path = '$CONFIG_TOML'
server_path = '$PLUGIN_ROOT/mcp/server.py'

# Read existing file or start empty
try:
    with open(config_path) as f:
        text = f.read()
except FileNotFoundError:
    text = ''

# Remove [mcp_servers.ai-memory...] blocks (main + sub-tables like .env)
lines = text.split('\n')
output = []
skip = False
for line in lines:
    if re.match(r'^\[mcp_servers\.ai-memory', line):
        skip = True
        continue
    if skip and re.match(r'^\[', line):
        skip = False
    if skip:
        continue
    output.append(line)

# Remove trailing blank lines
while output and output[-1].strip() == '':
    output.pop()

result = '\n'.join(output)
if result:
    result += '\n'

# Append new MCP section
result += f'''
[mcp_servers.ai-memory]
command = \"python3\"
args = [\"{server_path}\"]
'''

with open(config_path, 'w') as f:
    f.write(result)
"

echo "  ✓ MCP server configured in config.toml"

# ---------------------------------------------------------------------------
# 2. Hooks merge
# ---------------------------------------------------------------------------

HOOKS_FILE="$CODEX_DIR/hooks.json"
PLUGIN_HOOKS="$PLUGIN_ROOT/.codex/hooks.json"

if [ -f "$HOOKS_FILE" ]; then
    cp "$HOOKS_FILE" "$HOOKS_FILE.bak.$(date +%s)"
fi

python3 -c "
import json, sys

user_path = '$HOOKS_FILE'
plugin_path = '$PLUGIN_HOOKS'

with open(plugin_path) as f:
    plugin_data = json.load(f)

try:
    with open(user_path) as f:
        user_data = json.load(f)
except (FileNotFoundError, json.JSONDecodeError):
    user_data = {}

if 'hooks' not in user_data:
    user_data['hooks'] = {}

for event_type, plugin_entries in plugin_data.get('hooks', {}).items():
    existing = user_data['hooks'].get(event_type, [])

    # Remove old ai-memory entries (identified by AI_MEMORY_PLUGIN_ROOT in command)
    existing = [
        entry for entry in existing
        if not any(
            'AI_MEMORY_PLUGIN_ROOT' in h.get('command', '')
            for h in entry.get('hooks', [])
        )
    ]

    # Append plugin entries
    existing.extend(plugin_entries)
    user_data['hooks'][event_type] = existing

with open(user_path, 'w') as f:
    json.dump(user_data, f, indent=2)
    f.write('\n')
"

echo "  ✓ Hooks merged into hooks.json"

# ---------------------------------------------------------------------------
# 3. Environment variables in shell profile
# ---------------------------------------------------------------------------

# Detect shell profile
case "$(basename "${SHELL:-/bin/zsh}")" in
    zsh)  PROFILE="$HOME/.zshrc" ;;
    bash) PROFILE="$HOME/.bashrc" ;;
    *)    PROFILE="$HOME/.profile" ;;
esac

# Read defaults from Claude settings.json if available
CLAUDE_SETTINGS="$HOME/.claude/settings.json"
AI_MEMORY_DIR=""
AI_MEMORY_LLM=""
AI_MEMORY_LLM_PROVIDER=""
AI_MEMORY_EMBEDDING=""
OPENAI_API_KEY=""

if [ -f "$CLAUDE_SETTINGS" ]; then
    eval "$(python3 -c "
import json, sys, os

try:
    with open('$CLAUDE_SETTINGS') as f:
        env = json.load(f).get('env', {})

    mapping = {
        'AI_MEMORY_DIR': 'AI_MEMORY_DIR',
        'AI_MEMORY_LLM': 'AI_MEMORY_LLM',
        'AI_MEMORY_LLM_PROVIDER': 'AI_MEMORY_LLM_PROVIDER',
        'AI_MEMORY_EMBEDDING': 'AI_MEMORY_EMBEDDING',
        'OPENAI_API_KEY': 'OPENAI_API_KEY',
    }
    for key, var in mapping.items():
        val = env.get(key, '')
        if isinstance(val, bool):
            val = 'true' if val else 'false'
        if val:
            # Shell-safe quoting
            val = str(val).replace(\"'\", \"'\\\\''\" )
            print(f\"{var}='{val}'\")
except Exception:
    pass
")"
fi

# Build the env block
BEGIN_MARKER="# >>> ai-memory >>>"
END_MARKER="# <<< ai-memory <<<"

ENV_BLOCK="$BEGIN_MARKER
export AI_MEMORY_PLUGIN_ROOT=\"$PLUGIN_ROOT\""

[ -n "$AI_MEMORY_DIR" ]          && ENV_BLOCK="$ENV_BLOCK
export AI_MEMORY_DIR=\"$AI_MEMORY_DIR\""

[ -n "$AI_MEMORY_LLM" ]          && ENV_BLOCK="$ENV_BLOCK
export AI_MEMORY_LLM=\"$AI_MEMORY_LLM\""

[ -n "$AI_MEMORY_LLM_PROVIDER" ] && ENV_BLOCK="$ENV_BLOCK
export AI_MEMORY_LLM_PROVIDER=\"$AI_MEMORY_LLM_PROVIDER\""

[ -n "$AI_MEMORY_EMBEDDING" ]    && ENV_BLOCK="$ENV_BLOCK
export AI_MEMORY_EMBEDDING=\"$AI_MEMORY_EMBEDDING\""

[ -n "$OPENAI_API_KEY" ]         && ENV_BLOCK="$ENV_BLOCK
export OPENAI_API_KEY=\"$OPENAI_API_KEY\""

ENV_BLOCK="$ENV_BLOCK
$END_MARKER"

# Remove old block and append new one
if [ -f "$PROFILE" ]; then
    # Remove existing ai-memory block (between markers)
    python3 -c "
import re

with open('$PROFILE') as f:
    text = f.read()

# Remove old block including markers
pattern = r'\n?# >>> ai-memory >>>\n.*?# <<< ai-memory <<<\n?'
text = re.sub(pattern, '', text, flags=re.DOTALL)

with open('$PROFILE', 'w') as f:
    f.write(text)
"
fi

# Append new block
echo "" >> "$PROFILE"
echo "$ENV_BLOCK" >> "$PROFILE"

echo "  ✓ Environment variables set in $(basename "$PROFILE")"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
echo "Done! Run 'source $PROFILE' or restart your terminal."
echo ""
echo "Configured:"
echo "  MCP server:  $PLUGIN_ROOT/mcp/server.py"
echo "  Hooks:       $HOOKS_FILE"
echo "  Environment: $PROFILE"
