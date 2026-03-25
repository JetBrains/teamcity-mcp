#!/usr/bin/env bash
# Stops a TeamCity server previously started by setup-test-server.sh.
#
# Optional environment:
#   TC_HOME   — TeamCity installation directory (default: read from .test-server.env)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/../.test-server.env"

if [ -z "${TC_HOME:-}" ] && [ -f "$ENV_FILE" ]; then
    # shellcheck source=/dev/null
    source "$ENV_FILE"
fi

TC_HOME="${TC_HOME:-teamcity-home}"

echo "=== Stopping TeamCity server ==="
if [ -f "$TC_HOME/bin/teamcity-server.sh" ]; then
    "$TC_HOME/bin/teamcity-server.sh" stop
    echo "TeamCity server stopped"
else
    echo "No TeamCity installation found at $TC_HOME"
fi
