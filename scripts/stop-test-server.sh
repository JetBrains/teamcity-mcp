#!/usr/bin/env bash
# Stops a TeamCity server previously started by setup-test-server.sh.
#
# Optional environment:
#   TC_HOME   — TeamCity installation directory (default: read from .test-server.env)
#   TC_PORT   — server port (default: read from .test-server.env)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/../.test-server.env"

if [ -f "$ENV_FILE" ]; then
    # shellcheck source=/dev/null
    source "$ENV_FILE"
fi

TC_HOME="${TC_HOME:-teamcity-home}"
TC_PORT="${TC_PORT:-8211}"

echo "=== Stopping TeamCity server ==="
if [ -f "$TC_HOME/bin/teamcity-server.sh" ]; then
    "$TC_HOME/bin/teamcity-server.sh" stop 2>/dev/null || true
    sleep 5
fi

# Fallback: kill anything still holding the test port
if command -v lsof &>/dev/null; then
    lsof -ti :"$TC_PORT" | xargs kill -9 2>/dev/null || true
elif command -v fuser &>/dev/null; then
    fuser -k "${TC_PORT}/tcp" 2>/dev/null || true
fi

# Wait until the port is actually free
for _i in $(seq 1 30); do
    if ! lsof -ti :"$TC_PORT" &>/dev/null 2>&1; then
        echo "TeamCity server stopped (port $TC_PORT is free)"
        exit 0
    fi
    sleep 1
done

echo "WARNING: port $TC_PORT may still be in use after stop attempt"
