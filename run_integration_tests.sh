#!/usr/bin/env bash
# Run MCP integration and e2e tests against a TeamCity server.
#
# Mode 1 — Against an already-running server:
#   TC_SERVER_URL=http://localhost:8111 TC_HOME=teamcity-home TC_SERVER_TOKEN=eyJ... ./run_integration_tests.sh
#
# Mode 2 — Full lifecycle (setup server from dist, run tests, teardown):
#   TC_DIST=~/Downloads/TeamCity-222175.tar.gz ./run_integration_tests.sh
#
# In mode 2, the plugin is built first, a fresh TC server is started,
# integration and e2e tests run, and the server is stopped on exit.
#
# To skip rebuilding the plugin and use an existing one:
#   TC_DIST=~/Downloads/TeamCity-222175.tar.gz PLUGIN_ZIP=build/distributions/mcp.zip ./run_integration_tests.sh
#
# Any extra arguments are passed to both Gradle test tasks, e.g.:
#   TC_DIST=~/Downloads/TeamCity-222175.tar.gz ./run_integration_tests.sh --tests "McpStressTest"

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ── Mode 2: full lifecycle ───────────────────────────────────────────────────

if [ -n "${TC_DIST:-}" ]; then
    # Build the plugin if no pre-built zip is provided
    if [ -z "${PLUGIN_ZIP:-}" ]; then
        echo "=== Building MCP plugin ==="
        ./gradlew serverPlugin
        PLUGIN_ZIP="$(find build -name 'mcp.zip' -print -quit)"
        if [ -z "$PLUGIN_ZIP" ]; then
            echo "Failed to find mcp.zip after build"
            exit 1
        fi
    fi

    export TC_DIST PLUGIN_ZIP
    scripts/setup-test-server.sh

    # Source the generated env file
    # shellcheck source=/dev/null
    source .test-server.env
    export TC_SERVER_URL TC_SERVER_TOKEN TC_SERVER_RESTRICTED_TOKEN TC_HOME

    # Ensure server is stopped on exit
    trap 'scripts/stop-test-server.sh' EXIT
fi

# ── Run tests ────────────────────────────────────────────────────────────────

: "${TC_SERVER_URL:?TC_SERVER_URL must be set (or provide TC_DIST for automatic setup)}"
: "${TC_SERVER_TOKEN:?TC_SERVER_TOKEN must be set (or provide TC_DIST for automatic setup)}"
: "${TC_HOME:?TC_HOME must be set}"

GRADLE_ARGS=(
    -DTC_SERVER_URL="$TC_SERVER_URL"
    -DTC_SERVER_TOKEN="$TC_SERVER_TOKEN"
    -DTC_HOME="$TC_HOME"
)

if [ -n "${TC_SERVER_RESTRICTED_TOKEN:-}" ]; then
    GRADLE_ARGS+=(-DTC_SERVER_RESTRICTED_TOKEN="$TC_SERVER_RESTRICTED_TOKEN")
fi

EXIT_CODE=0

echo "=== Running integration tests ==="
./gradlew integrationTest "${GRADLE_ARGS[@]}" "$@" || EXIT_CODE=$?

echo "=== Running e2e tests ==="
./gradlew e2eTest "${GRADLE_ARGS[@]}" "$@" || EXIT_CODE=$?

exit $EXIT_CODE
