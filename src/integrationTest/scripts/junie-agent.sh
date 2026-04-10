#!/usr/bin/env bash
set -euo pipefail

# JetBrains Junie agent operations for E2E tests.
# Usage: bash junie-agent.sh <subcommand> [args...]
#
# Subcommands:
#   configure  <container> <mcp-url> <bearer-token>   — write .junie/mcp/mcp.json
#   run-prompt <container> <prompt> <api-key>          — run a prompt via junie CLI

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/common.sh"

WORK_DIR="/home/agent/project"

cmd_configure() {
    local container="$1"
    local mcp_url="$2"
    local bearer_token="$3"

    local docker_url
    docker_url="$(to_docker_url "$mcp_url")"

    # Write .junie/mcp/mcp.json via env vars to avoid shell expansion of special chars in the token
    docker exec -i \
        -e "_URL=$docker_url" \
        -e "_TOKEN=$bearer_token" \
        -e "_DIR=$WORK_DIR" \
        "$container" \
        bash -c 'mkdir -p "$_DIR/.junie/mcp" && printf "{\"mcpServers\":{\"teamcity\":{\"url\":\"%s\",\"headers\":{\"Authorization\":\"Bearer %s\"}}}}\n" "$_URL" "$_TOKEN" > "$_DIR/.junie/mcp/mcp.json"'
}

cmd_run_prompt() {
    local container="$1"
    local prompt="$2"
    local api_key="$3"

    # Junie's --timeout flag is unreliable; wrap with timeout to guarantee termination.
    # Exit 124 (SIGTERM from timeout) or 137 (SIGKILL) are expected.
    docker exec \
        -w "$WORK_DIR" \
        -e "JUNIE_API_KEY=$api_key" \
        -e "PATH=/home/agent/.npm-global/bin:/home/agent/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
        "$container" \
        timeout 180 junie \
            --auth="$api_key" \
            --output-format json \
            "$prompt"
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
