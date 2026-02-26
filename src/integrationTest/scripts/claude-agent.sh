#!/usr/bin/env bash
set -euo pipefail

# Claude Code agent operations for E2E tests.
# Usage: bash claude-agent.sh <subcommand> [args...]
#
# Subcommands:
#   configure  <container> <mcp-url> <bearer-token>   — write .mcp.json
#   run-prompt <container> <prompt> <api-key>          — run a prompt via claude CLI

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/common.sh"

WORK_DIR="/home/agent/project"

cmd_configure() {
    local container="$1"
    local mcp_url="$2"
    local bearer_token="$3"

    local docker_url
    docker_url="$(to_docker_url "$mcp_url")"

    # Write .mcp.json via env vars to avoid shell expansion of special chars in the token
    docker exec -i \
        -e "_URL=$docker_url" \
        -e "_TOKEN=$bearer_token" \
        -e "_DIR=$WORK_DIR" \
        "$container" \
        bash -c 'mkdir -p "$_DIR" && printf "{\"mcpServers\":{\"teamcity\":{\"type\":\"http\",\"url\":\"%s\",\"headers\":{\"Authorization\":\"Bearer %s\"}}}}\n" "$_URL" "$_TOKEN" > "$_DIR/.mcp.json"'
}

cmd_run_prompt() {
    local container="$1"
    local prompt="$2"
    local api_key="$3"

    docker exec \
        -w "$WORK_DIR" \
        -e "ANTHROPIC_API_KEY=$api_key" \
        "$container" \
        claude -p "$prompt" \
            --output-format stream-json \
            --verbose \
            --dangerously-skip-permissions
}

# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------

subcmd="${1:-}"
shift || true

case "$subcmd" in
    configure)  cmd_configure "$@" ;;
    run-prompt) cmd_run_prompt "$@" ;;
    *)
        echo "Usage: $0 {configure|run-prompt} [args...]" >&2
        exit 1
        ;;
esac
