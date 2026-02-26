#!/usr/bin/env bash
set -euo pipefail

# OpenAI Codex agent operations for E2E tests.
# Usage: bash codex-agent.sh <subcommand> [args...]
#
# Subcommands:
#   configure  <container> <mcp-url> <bearer-token> <openai-api-key>  — write auth.json + register MCP
#   run-prompt <container> <prompt> <openai-api-key> <bearer-token>   — run a prompt via codex CLI

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/common.sh"

WORK_DIR="/home/agent/project"

cmd_configure() {
    local container="$1"
    local mcp_url="$2"
    local bearer_token="$3"
    local openai_api_key="$4"

    local docker_url
    docker_url="$(to_docker_url "$mcp_url")"

    # Write auth.json via env var to avoid shell expansion of special chars in the key
    docker exec -i \
        -e "_API_KEY=$openai_api_key" \
        "$container" \
        bash -c 'mkdir -p /home/agent/.codex && printf "{\"auth_mode\":\"apikey\",\"OPENAI_API_KEY\":\"%s\"}\n" "$_API_KEY" > /home/agent/.codex/auth.json'

    # Register MCP server
    docker exec \
        -w "$WORK_DIR" \
        "$container" \
        codex mcp add teamcity \
            --url "$docker_url" \
            --bearer-token-env-var TC_BEARER_TOKEN
}

cmd_run_prompt() {
    local container="$1"
    local prompt="$2"
    local openai_api_key="$3"
    local bearer_token="$4"

    docker exec \
        -w "$WORK_DIR" \
        -e "OPENAI_API_KEY=$openai_api_key" \
        -e "TC_BEARER_TOKEN=$bearer_token" \
        "$container" \
        codex exec \
            --dangerously-bypass-approvals-and-sandbox \
            --skip-git-repo-check \
            --json \
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
