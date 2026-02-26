#!/usr/bin/env bash
set -euo pipefail

# Shared Docker operations for E2E tests.
# Usage: bash common.sh <subcommand> [args...]
#
# Subcommands:
#   check-docker                  — verify Docker daemon is reachable
#   build <dockerfile-dir> <tag>  — build a Docker image
#   start <container-name> <tag>  — run a container in detached mode (sleep infinity)
#   write-file <container> <path> — write stdin to a file inside the container
#   stop <container-name>         — force-remove the container

# ---------------------------------------------------------------------------
# Helper: translate localhost URLs to host.docker.internal for container use
# ---------------------------------------------------------------------------
to_docker_url() {
    local url="$1"
    echo "$url" | sed -E 's#(https?://)localhost([:/?]|$)#\1host.docker.internal\2#; s#(https?://)127\.0\.0\.1([:/?]|$)#\1host.docker.internal\2#'
}

# ---------------------------------------------------------------------------
# Subcommands
# ---------------------------------------------------------------------------

cmd_check_docker() {
    docker info >/dev/null 2>&1
    echo "Docker is available"
}

cmd_build() {
    local dir="$1"
    local tag="$2"
    docker build -t "$tag" "$dir"
}

cmd_start() {
    local name="$1"
    local tag="$2"

    # Remove stale container from a previous crashed run, if any
    docker rm -f "$name" 2>/dev/null || true

    local cmd=(docker run -d --name "$name")
    # On Linux, host.docker.internal needs explicit mapping
    if [ "$(uname -s)" = "Linux" ]; then
        cmd+=("--add-host=host.docker.internal:host-gateway")
    fi
    cmd+=("$tag" sleep infinity)

    "${cmd[@]}"
}

cmd_write_file() {
    local name="$1"
    local path="$2"
    # Content is read from stdin. Path is passed via env to avoid shell injection.
    docker exec -i -e "_TARGET_PATH=$path" "$name" \
        bash -c 'mkdir -p "$(dirname "$_TARGET_PATH")" && cat > "$_TARGET_PATH"'
}

cmd_stop() {
    local name="$1"
    docker rm -f "$name" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# Dispatch (only when executed directly, not when sourced)
# ---------------------------------------------------------------------------

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    subcmd="${1:-}"
    shift || true

    case "$subcmd" in
        check-docker) cmd_check_docker ;;
        build)        cmd_build "$@" ;;
        start)        cmd_start "$@" ;;
        write-file)   cmd_write_file "$@" ;;
        stop)         cmd_stop "$@" ;;
        *)
            echo "Usage: $0 {check-docker|build|start|write-file|stop} [args...]" >&2
            exit 1
            ;;
    esac
fi
