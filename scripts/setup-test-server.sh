#!/usr/bin/env bash
# Sets up a TeamCity server from a distribution archive with the MCP plugin installed,
# starts it, creates test fixtures, and prints the connection parameters.
#
# Required environment:
#   TC_DIST        — path to TeamCity-*.tar.gz distribution archive
#   PLUGIN_ZIP     — path to the built mcp.zip plugin file
#
# Optional environment:
#   TC_HOME        — where to extract TC (default: ./teamcity-home)
#   JAVA_HOME      — JDK to use (must be set or already on PATH)
#   TC_PORT        — server port (default: 8211, to avoid conflicts with a local TC on 8111)
#
# Outputs (to stdout and .test-server.env):
#   TC_SERVER_URL=...
#   TC_SERVER_TOKEN=...
#   TC_SERVER_RESTRICTED_TOKEN=...
#   TC_HOME=...
#   TC_DATA_PATH=...   (TeamCity data dir, contains config/internal.properties)
#
# On TeamCity CI, also emits ##teamcity[setParameter ...] service messages.

set -euo pipefail

: "${TC_DIST:?TC_DIST must be set to the path of TeamCity-*.tar.gz}"
: "${PLUGIN_ZIP:?PLUGIN_ZIP must be set to the path of mcp.zip}"

# Resolve glob patterns (e.g. temp/TeamCity-*.tar.gz)
# shellcheck disable=SC2086
TC_DIST_RESOLVED=( $TC_DIST )
if [ ${#TC_DIST_RESOLVED[@]} -eq 0 ]; then
    echo "ERROR: TC_DIST pattern '$TC_DIST' did not match any files"
    exit 1
fi
TC_DIST="${TC_DIST_RESOLVED[0]}"

TC_HOME="${TC_HOME:-teamcity-home}"
TC_PORT="${TC_PORT:-8211}"
TC_BASE_URL="http://localhost:${TC_PORT}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Helpers ──────────────────────────────────────────────────────────────────

is_teamcity_ci() { [ -n "${TEAMCITY_VERSION:-}" ]; }

tc_service_message() {
    if is_teamcity_ci; then
        echo "##teamcity[$*]"
    fi
}

# ── Stop any previous instance ───────────────────────────────────────────────

echo "=== Stopping any previous TeamCity instance ==="
if [ -f "$TC_HOME/bin/teamcity-server.sh" ]; then
    "$TC_HOME/bin/teamcity-server.sh" stop 2>/dev/null || true
    sleep 5
fi
# Kill anything still holding the port. Use lsof (works on both macOS and Linux).
if command -v lsof &>/dev/null; then
    lsof -ti :"$TC_PORT" | xargs kill -9 2>/dev/null || true
elif command -v fuser &>/dev/null; then
    fuser -k "${TC_PORT}/tcp" 2>/dev/null || true
fi
# Wait until the port is actually free
for _i in $(seq 1 30); do
    if ! lsof -ti :"$TC_PORT" &>/dev/null; then break; fi
    sleep 1
done

# ── Clean & extract ──────────────────────────────────────────────────────────

echo "=== Cleaning previous installation ==="
rm -rf "$TC_HOME"

echo "=== Extracting TeamCity distribution ==="
mkdir -p "$TC_HOME"
tar xzf "$TC_DIST" -C "$TC_HOME" --strip-components=1

echo "=== Configuring server port ==="
# Replace HTTP and shutdown ports so the test server doesn't collide with a local TC.
sed -i.bak -e "s/port=\"8111\"/port=\"${TC_PORT}\"/g" \
           -e "s/port=\"8105\"/port=\"$((TC_PORT + 100))\"/g" \
           "$TC_HOME/conf/server.xml"
rm -f "$TC_HOME/conf/server.xml.bak"

echo "=== Installing MCP plugin ==="
mkdir -p "$TC_HOME/webapps/ROOT/WEB-INF/plugins"
cp "$PLUGIN_ZIP" "$TC_HOME/webapps/ROOT/WEB-INF/plugins/"

# ── Configure data directory ─────────────────────────────────────────────────

echo "=== Pre-creating data directory ==="
export TEAMCITY_DATA_PATH="$(cd "$TC_HOME" && pwd)/.BuildServer"
mkdir -p "$TEAMCITY_DATA_PATH/lib/jdbc"
mkdir -p "$TEAMCITY_DATA_PATH/config"
mkdir -p "$TEAMCITY_DATA_PATH/system"

echo "=== Writing internal properties ==="
# teamcity.development.mode=true makes the plugin advertise the introduce_yourself
# tool and resource (used by smoke + e2e tests for agent-discovery coverage).
cat > "$TEAMCITY_DATA_PATH/config/internal.properties" <<PROPS
teamcity.licenseAgreement.accepted=true
teamcity.ai.mcp.braveMode.enabled=true
teamcity.ai.mcp.pipeline.enabled=true
teamcity.internal.fus.debugToLogs=true
teamcity.internal.fus.flushInterval=5000
teamcity.development.mode=true
PROPS

# ── Start server ─────────────────────────────────────────────────────────────

echo "=== Starting TeamCity server ==="
export TEAMCITY_SERVER_OPTS="-Dteamcity.startup.maintenance=false"
"$TC_HOME/bin/teamcity-server.sh" start

echo "=== Waiting for REST API ==="
for i in $(seq 1 240); do
    if curl -sf "${TC_BASE_URL}/app/rest/server/version" > /dev/null 2>&1; then
        echo "REST API is available after ${i} seconds"
        break
    fi
    if [ "$i" -eq 240 ]; then
        echo "TeamCity REST API not available within 240 seconds"
        cat "$TC_HOME/logs/teamcity-server.log" || true
        exit 1
    fi
    sleep 1
done

# ── Read super user token ────────────────────────────────────────────────────

echo "=== Reading super user token ==="
SUPER_TOKEN=""
for logfile in "$TC_HOME/logs/teamcity-server.log" "$TC_HOME/logs/teamcity-auth.log"; do
    if [ -f "$logfile" ]; then
        SUPER_TOKEN=$(grep -oE 'Super user authentication token: [0-9]+' "$logfile" | tail -1 | grep -oE '[0-9]+$') || true
        [ -n "$SUPER_TOKEN" ] && break
    fi
done
if [ -z "$SUPER_TOKEN" ]; then
    echo "Failed to read super user token"
    cat "$TC_HOME/logs/teamcity-server.log" || true
    exit 1
fi
echo "Super user token obtained"

# ── Create admin user ────────────────────────────────────────────────────────

echo "=== Creating admin user ==="
curl -sf -u ":${SUPER_TOKEN}" -X POST "${TC_BASE_URL}/httpAuth/app/rest/users" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -d '{"username":"admin","password":"admin","roles":{"role":[{"roleId":"SYSTEM_ADMIN","scope":"g"}]}}'
echo ""
echo "Admin user created"

echo "=== Enabling debug-all logging preset ==="
curl -sf -u "admin:admin" -X POST "${TC_BASE_URL}/httpAuth/admin/diagnostic.html" \
    -d "actionName=loadPreset&loggingPreset=debug-all"
echo "debug-all logging preset activated"

# ── Create permission visibility fixtures ────────────────────────────────────

echo "=== Creating permission visibility fixtures ==="
for project in '{"id":"McpPermissionVisible","name":"MCP Permission Visible","parentProject":{"id":"_Root"}}' \
               '{"id":"McpPermissionHidden","name":"MCP Permission Hidden","parentProject":{"id":"_Root"}}'; do
    curl -sf -u "admin:admin" -X POST "${TC_BASE_URL}/httpAuth/app/rest/projects" \
        -H "Content-Type: application/json" -H "Accept: application/json" -d "$project"
    echo ""
done
for bt in '{"id":"McpPermissionVisible_Build","name":"Visible Build","project":{"id":"McpPermissionVisible"}}' \
          '{"id":"McpPermissionHidden_Build","name":"Hidden Build","project":{"id":"McpPermissionHidden"}}'; do
    curl -sf -u "admin:admin" -X POST "${TC_BASE_URL}/httpAuth/app/rest/buildTypes" \
        -H "Content-Type: application/json" -H "Accept: application/json" -d "$bt"
    echo ""
done
echo "Permission visibility fixtures created"

# ── Create pipeline fixture ──────────────────────────────────────────────────

echo "=== Creating pipeline fixture ==="
curl -sf -u "admin:admin" -X POST "${TC_BASE_URL}/app/pipeline" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -d '{
      "name":"MCP Seeded Pipeline",
      "yaml":"jobs:\n  Job1:\n    name: Seed Job\n    runs-on: Linux-Medium\n    steps: []\n",
      "additionalVcsRoots":[],
      "triggers":[],
      "integrations":[],
      "notifications":[]
    }'
echo ""
echo "Pipeline fixture created"

# ── Create access tokens ────────────────────────────────────────────────────

echo "=== Creating access token ==="
TOKEN_RESPONSE=$(curl -sf -u "admin:admin" -X POST \
    "${TC_BASE_URL}/httpAuth/app/rest/users/username:admin/tokens" \
    -H "Content-Type: application/json" -H "Accept: application/json" \
    -d '{"name":"integration-test-token"}')
ACCESS_TOKEN=$(echo "$TOKEN_RESPONSE" | grep -oE '"value"\s*:\s*"[^"]+"' | head -1 | grep -oE '"[^"]+"\s*$' | tr -d '"')
if [ -z "$ACCESS_TOKEN" ]; then
    echo "Failed to create access token. Response: $TOKEN_RESPONSE"
    exit 1
fi
echo "Access token created"

echo "=== Creating restricted access token ==="
RESTRICTED_TOKEN_RESPONSE=$(curl -sf -u "admin:admin" -X POST \
    "${TC_BASE_URL}/httpAuth/app/rest/users/username:admin/tokens" \
    -H "Content-Type: application/json" -H "Accept: application/json" \
    -d '{"name":"integration-test-restricted-token","permissionRestrictions":{"permissionRestriction":[{"project":{"id":"McpPermissionVisible"},"permission":{"id":"view_project"}}]}}')
RESTRICTED_ACCESS_TOKEN=$(echo "$RESTRICTED_TOKEN_RESPONSE" | grep -oE '"value"\s*:\s*"[^"]+"' | head -1 | grep -oE '"[^"]+"\s*$' | tr -d '"')
if [ -z "$RESTRICTED_ACCESS_TOKEN" ]; then
    echo "Failed to create restricted access token. Response: $RESTRICTED_TOKEN_RESPONSE"
    exit 1
fi
echo "Restricted access token created"

# ── Export results ───────────────────────────────────────────────────────────

echo "=== Server ready ==="
echo "TC_HOME=${TC_HOME}"
echo "TC_DATA_PATH=${TEAMCITY_DATA_PATH}"
echo "TC_SERVER_URL=${TC_BASE_URL}"
echo "TC_SERVER_TOKEN=${ACCESS_TOKEN}"
echo "TC_SERVER_RESTRICTED_TOKEN=${RESTRICTED_ACCESS_TOKEN}"

tc_service_message "setParameter name='env.TC_HOME' value='${TC_HOME}'"
tc_service_message "setParameter name='env.TC_DATA_PATH' value='${TEAMCITY_DATA_PATH}'"
tc_service_message "setParameter name='env.TC_SERVER_URL' value='${TC_BASE_URL}'"
tc_service_message "setParameter name='env.TC_SERVER_TOKEN' value='${ACCESS_TOKEN}'"
tc_service_message "setParameter name='env.TC_SERVER_RESTRICTED_TOKEN' value='${RESTRICTED_ACCESS_TOKEN}'"

# Write to a file that other scripts can source
ENV_FILE="${SCRIPT_DIR}/../.test-server.env"
cat > "$ENV_FILE" <<EOF
TC_SERVER_URL=${TC_BASE_URL}
TC_SERVER_TOKEN=${ACCESS_TOKEN}
TC_SERVER_RESTRICTED_TOKEN=${RESTRICTED_ACCESS_TOKEN}
TC_HOME=${TC_HOME}
TC_DATA_PATH=${TEAMCITY_DATA_PATH}
TC_PORT=${TC_PORT}
EOF
echo "Connection parameters written to ${ENV_FILE}"
