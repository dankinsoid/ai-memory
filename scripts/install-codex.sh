#!/usr/bin/env bash
# @ai-generated(solo)
# Install ai-memory plugin for Codex CLI.
#
# Configures MCP server and merges hooks with baked-in paths/env.
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

# Enable hooks. Canonical key is features.hooks; codex_hooks is a deprecated
# alias — migrate it so the two never coexist as duplicate keys.
if re.search(r'^features\.hooks\s*=', result, re.MULTILINE):
    result = re.sub(r'^features\.hooks\s*=.*$', 'features.hooks = true', result, flags=re.MULTILINE)
elif re.search(r'^features\.codex_hooks\s*=', result, re.MULTILINE):
    result = re.sub(r'^features\.codex_hooks\s*=.*$', 'features.hooks = true', result, flags=re.MULTILINE)
elif re.search(r'^\[features\]\s*$', result, re.MULTILINE):
    # Table form: set the key inside [features] instead of adding a dotted duplicate.
    lines = result.split('\n')
    out, in_features, done = [], False, False
    for line in lines:
        if re.match(r'^\[features\]\s*$', line):
            in_features = True
            out.append(line)
            continue
        if in_features and re.match(r'^\[', line):
            if not done:
                out.append('hooks = true')
                done = True
            in_features = False
        if in_features and re.match(r'^(hooks|codex_hooks)\s*=', line):
            if done:
                continue
            line, done = 'hooks = true', True
        out.append(line)
    if in_features and not done:
        out.append('hooks = true')
    result = '\n'.join(out)
else:
    result = ('features.hooks = true\n\n' + result) if result else 'features.hooks = true\n'

if result:
    result += '\n'

# Read AI_MEMORY_* env vars from Claude settings.json for MCP env section
mcp_env = {}
try:
    import json as _json
    with open(os.path.expanduser('~/.claude/settings.json')) as f:
        claude_env = _json.load(f).get('env', {})
    for k, v in claude_env.items():
        if k.startswith('AI_MEMORY_') or k == 'OPENAI_API_KEY':
            if isinstance(v, bool):
                v = 'true' if v else 'false'
            mcp_env[k] = str(v)
except Exception:
    pass

# Append new MCP section
result += f'''
[mcp_servers.ai-memory]
command = \"python3\"
args = [\"{server_path}\"]
'''

if mcp_env:
    result += '[mcp_servers.ai-memory.env]\n'
    for k, v in mcp_env.items():
        result += f'{k} = \"{v}\"\n'
    result += '\n'

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
import copy
import json
import os
import re
import shlex
import sys

user_path = '$HOOKS_FILE'
plugin_path = '$PLUGIN_HOOKS'
plugin_root = '$PLUGIN_ROOT'

with open(plugin_path) as f:
    plugin_data = json.load(f)

try:
    with open(user_path) as f:
        user_data = json.load(f)
except (FileNotFoundError, json.JSONDecodeError):
    user_data = {}

# Read hook env vars from Claude settings.json. These are baked into hook
# commands so hooks do not depend on shell rc files like ~/.zshrc.
hook_env = {}
try:
    with open(os.path.expanduser('~/.claude/settings.json')) as f:
        claude_env = json.load(f).get('env', {})
    for key, value in claude_env.items():
        if key.startswith('AI_MEMORY_') or key == 'OPENAI_API_KEY':
            if isinstance(value, bool):
                value = 'true' if value else 'false'
            hook_env[key] = str(value)
except Exception:
    pass


def rewrite_command(command: str) -> str:
    env_prefix = ' '.join(
        f'{key}={shlex.quote(value)}' for key, value in sorted(hook_env.items())
    )

    def repl(match):
        suffix = match.group(1)
        return shlex.quote(f'{plugin_root}/{suffix}')

    # [$] not \$: inside this double-quoted bash string \$ reaches Python as a bare $ anchor.
    command = re.sub(r'[$]AI_MEMORY_PLUGIN_ROOT/([^\s\"\']+)', repl, command)
    return f'{env_prefix} {command}' if env_prefix else command

if 'hooks' not in user_data:
    user_data['hooks'] = {}

for event_type, plugin_entries in plugin_data.get('hooks', {}).items():
    existing = user_data['hooks'].get(event_type, [])

    # Drop old ai-memory hooks before appending freshly rendered ones. Filter
    # per hook, not per entry: users add their own hooks to the same group.
    def is_ours(h):
        cmd = h.get('command', '')
        return 'AI_MEMORY_PLUGIN_ROOT' in cmd or plugin_root in cmd

    kept = []
    for entry in existing:
        hooks = [h for h in entry.get('hooks', []) if not is_ours(h)]
        if hooks:
            kept.append({**entry, 'hooks': hooks})
    existing = kept

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

with open(user_path, 'w') as f:
    json.dump(user_data, f, indent=2)
    f.write('\n')
"

echo "  ✓ Hooks merged into hooks.json"

# ---------------------------------------------------------------------------
# 3. Summary
# ---------------------------------------------------------------------------
echo "  ✓ Hook commands use absolute paths and baked-in AI_MEMORY_* env"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
echo "Done! Restart Codex if it was already running."
echo ""
echo "Configured:"
echo "  MCP server:  $PLUGIN_ROOT/mcp/server.py"
echo "  Hooks:       $HOOKS_FILE"
echo "  Hook paths:  baked into hooks.json"
