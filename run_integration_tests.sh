#!/usr/bin/env bash
# Run MCP integration tests against a prepared TeamCity server.
#
# Usage:
#   TC_SERVER_URL=http://localhost:8111 \
#   TC_SERVER_TOKEN=eyJ... \
#   ./run_integration_tests.sh
set -euo pipefail

: "${TC_SERVER_URL:?TC_SERVER_URL must be set}"
: "${TC_SERVER_TOKEN:?TC_SERVER_TOKEN must be set}"

cd "$(dirname "${BASH_SOURCE[0]}")"

./gradlew integrationTest \
    -DTC_SERVER_URL="$TC_SERVER_URL" \
    -DTC_SERVER_TOKEN="$TC_SERVER_TOKEN" \
    "$@"
