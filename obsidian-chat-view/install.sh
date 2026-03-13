#!/usr/bin/env bash
# @ai-generated(solo)
# Install the chat-view Obsidian plugin by symlinking into the vault.
#
# Usage:
#   ./install.sh                          # auto-detect vault
#   ./install.sh ~/Documents/MyVault      # explicit vault path

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLUGIN_NAME="chat-view"

# Resolve vault path
if [[ $# -ge 1 ]]; then
    VAULT="$1"
else
    # Default: first Obsidian vault found in ~/Documents
    VAULT="$(find ~/Documents -maxdepth 2 -name ".obsidian" -type d 2>/dev/null | head -1 | xargs dirname)"
    if [[ -z "$VAULT" ]]; then
        echo "No Obsidian vault found in ~/Documents. Pass vault path as argument."
        exit 1
    fi
fi

PLUGINS_DIR="$VAULT/.obsidian/plugins"
TARGET="$PLUGINS_DIR/$PLUGIN_NAME"

mkdir -p "$PLUGINS_DIR"

# Remove existing install (symlink or directory)
if [[ -e "$TARGET" || -L "$TARGET" ]]; then
    rm -rf "$TARGET"
fi

ln -s "$SCRIPT_DIR" "$TARGET"
echo "Installed: $TARGET -> $SCRIPT_DIR"

# Register in community-plugins.json if not already there
CP_FILE="$VAULT/.obsidian/community-plugins.json"
if [[ -f "$CP_FILE" ]]; then
    if ! grep -q "\"$PLUGIN_NAME\"" "$CP_FILE"; then
        # Add plugin to the JSON array
        python3 -c "
import json, sys
with open('$CP_FILE') as f: plugins = json.load(f)
if '$PLUGIN_NAME' not in plugins:
    plugins.append('$PLUGIN_NAME')
    with open('$CP_FILE', 'w') as f: json.dump(plugins, f, indent=2)
    print('Registered in community-plugins.json')
"
    fi
else
    echo '["'"$PLUGIN_NAME"'"]' > "$CP_FILE"
    echo "Created community-plugins.json"
fi

echo "Done. Reload Obsidian to activate."
