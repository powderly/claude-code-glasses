#!/bin/bash
# Launch ccg with relay and auto-connect glasses
# Usage: ./scripts/launch.sh "your prompt here"
#   or pipe: claude --output-format stream-json --verbose -p "..." | ./scripts/launch.sh

set -e

export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

# Generate token
TOKEN=$(openssl rand -hex 16)

# Set up ADB reverse port forwarding
adb reverse tcp:9200 tcp:9200 2>/dev/null || echo "Warning: adb reverse failed (glasses may not be connected)"

# Launch HUD on glasses with token
adb shell am start -n com.ccg.glasses/.HudActivity --es url "ws://localhost:9200" --es token "$TOKEN" 2>/dev/null &

# Small delay to let HUD connect first
sleep 1

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TSX="$SCRIPT_DIR/node_modules/.bin/tsx"
CLI="$SCRIPT_DIR/apps/cli/src/index.ts"

if [ -n "$1" ]; then
    # Prompt provided as argument
    claude --output-format stream-json --verbose -p "$1" | CCG_TOKEN="$TOKEN" "$TSX" "$CLI" start --relay
else
    # Read from stdin (piped)
    CCG_TOKEN="$TOKEN" "$TSX" "$CLI" start --relay
fi
