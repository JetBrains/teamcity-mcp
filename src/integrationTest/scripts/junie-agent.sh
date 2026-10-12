#!/usr/bin/env bash
set -euo pipefail

# JetBrains Junie agent operations for E2E tests.
# Usage: bash junie-agent.sh <subcommand> [args...]
#
# Subcommands:
#   build      <dockerfile-dir> <tag>                  — build the image with the latest Junie release
#   configure  <container> <mcp-url> <bearer-token>   — write .junie/mcp/mcp.json
#   run-prompt <container> <prompt> <api-key>          — run a prompt via junie CLI

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/common.sh"

WORK_DIR="/home/agent/project"

cmd_build() {
    local dir="$1"
    local tag="$2"

    # Resolve the latest released version (same feed the official installer uses) so the
    # install layer is rebuilt whenever Junie is released, instead of being served from
    # a stale Docker cache.
    local version
    version="$(curl -fsSL https://raw.githubusercontent.com/jetbrains-junie/junie/main/update-info.jsonl \
        | sed -nE 's/.*"version":"([^"]+)".*/\1/p' | sort -V | tail -1)"
    if [ -z "$version" ]; then
        echo "Failed to resolve latest Junie version from update-info.jsonl" >&2
        exit 1
    fi
    echo "Using Junie $version"

    bash "$SCRIPT_DIR/common.sh" build "$dir" "$tag" --build-arg "JUNIE_LATEST=$version"
}

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
        -e "PATH=/home/agent/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
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
    build)      cmd_build "$@" ;;
    configure)  cmd_configure "$@" ;;
    run-prompt) cmd_run_prompt "$@" ;;
    *)
        echo "Usage: $0 {build|configure|run-prompt} [args...]" >&2
        exit 1
        ;;
esac
