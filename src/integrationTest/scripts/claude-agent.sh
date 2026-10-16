#!/usr/bin/env bash
set -euo pipefail

# Claude Code agent operations for E2E tests.
# Usage: bash claude-agent.sh <subcommand> [args...]
#
# Subcommands:
#   build      <dockerfile-dir> <tag>                  — build the image with the latest stable Claude Code release
#   configure  <container> <mcp-url> <bearer-token>   — write .mcp.json
#   run-prompt <container> <prompt> <api-key>          — run a prompt via claude CLI

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/common.sh"

WORK_DIR="/home/agent/project"

cmd_build() {
    local dir="$1"
    local tag="$2"

    # Resolve the current stable release (same endpoint the official installer uses). The
    # Dockerfile installs exactly this version, and the install layer is rebuilt whenever
    # it changes instead of being served from a stale Docker cache.
    local version
    version="$(curl -fsSL https://downloads.claude.ai/claude-code-releases/stable)"
    if [ -z "$version" ]; then
        echo "Failed to resolve the stable Claude Code version" >&2
        exit 1
    fi
    echo "Using Claude Code $version"

    bash "$SCRIPT_DIR/common.sh" build "$dir" "$tag" --build-arg "INSTALL_VERSION=$version"
}

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
    build)      cmd_build "$@" ;;
    configure)  cmd_configure "$@" ;;
    run-prompt) cmd_run_prompt "$@" ;;
    *)
        echo "Usage: $0 {build|configure|run-prompt} [args...]" >&2
        exit 1
        ;;
esac
