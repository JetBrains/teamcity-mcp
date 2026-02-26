#!/usr/bin/env bash
set -euo pipefail

# Google Gemini CLI agent operations for E2E tests.
# Usage: bash gemini-agent.sh <subcommand> [args...]
#
# Subcommands:
#   configure  <container> <mcp-url> <bearer-token> <gemini-api-key>  — register MCP + write settings
#   run-prompt <container> <prompt> <gemini-api-key>                  — run a prompt (with sandbox retry)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/common.sh"

WORK_DIR="/home/agent/project"

cmd_configure() {
    local container="$1"
    local mcp_url="$2"
    local bearer_token="$3"
    local gemini_api_key="$4"

    local docker_url
    docker_url="$(to_docker_url "$mcp_url")"

    # Write settings.json via env vars to avoid shell expansion of special chars in the token.
    # Uses httpUrl (Streamable HTTP transport) + headers for auth + trust to bypass confirmation prompts.
    docker exec -i \
        -e "_URL=$docker_url" \
        -e "_TOKEN=$bearer_token" \
        "$container" \
        bash -c 'mkdir -p /home/agent/.gemini && printf "{\"mcpServers\":{\"teamcity\":{\"httpUrl\":\"%s\",\"headers\":{\"Authorization\":\"Bearer %s\"},\"trust\":true}}}\n" "$_URL" "$_TOKEN" > /home/agent/.gemini/settings.json'
}

cmd_run_prompt() {
    local container="$1"
    local prompt="$2"
    local gemini_api_key="$3"

    # Try --sandbox-mode none first
    local output
    local exit_code=0
    output=$(docker exec \
        -w "$WORK_DIR" \
        -e "GEMINI_API_KEY=$gemini_api_key" \
        "$container" \
        timeout 120 gemini \
            --screen-reader true \
            --sandbox-mode none \
            --approval-mode yolo \
            --output-format stream-json \
            --prompt "$prompt" 2>&1) || exit_code=$?

    # If --sandbox-mode is not recognized, retry with --sandbox false
    if [ "$exit_code" -ne 0 ] && echo "$output" | grep -q "sandbox-mode"; then
        exec docker exec \
            -w "$WORK_DIR" \
            -e "GEMINI_API_KEY=$gemini_api_key" \
            "$container" \
            timeout 120 gemini \
                --screen-reader true \
                --sandbox false \
                --approval-mode yolo \
                --output-format stream-json \
                --prompt "$prompt"
    fi

    echo "$output"
    exit "$exit_code"
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
