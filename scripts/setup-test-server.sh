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
#   TC_PORT        — server port (default: 8111)
#   TC_MCP_TOOLS   — comma-separated list of enabled MCP tools
#   TC_MCP_RESOURCES — comma-separated list of enabled MCP resources
#
# Outputs (to stdout):
#   TC_SERVER_URL=...
#   TC_SERVER_TOKEN=...
#   TC_SERVER_RESTRICTED_TOKEN=...
#
# On TeamCity CI, also emits ##teamcity[setParameter ...] service messages.

set -euo pipefail

: "${TC_DIST:?TC_DIST must be set to the path of TeamCity-*.tar.gz}"
: "${PLUGIN_ZIP:?PLUGIN_ZIP must be set to the path of mcp.zip}"

TC_HOME="${TC_HOME:-teamcity-home}"
TC_PORT="${TC_PORT:-8111}"
TC_MCP_TOOLS="${TC_MCP_TOOLS:-feedback,teamcity_build_log,teamcity_rest_get,teamcity_rest_post,introduce_yourself}"
TC_MCP_RESOURCES="${TC_MCP_RESOURCES:-rest_api_guide,build_failure_analysis_guide,introduce_yourself}"
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
if command -v fuser &>/dev/null; then
    fuser -k "${TC_PORT}/tcp" 2>/dev/null || true
elif command -v lsof &>/dev/null; then
    lsof -ti :"$TC_PORT" | xargs kill 2>/dev/null || true
fi

# ── Clean & extract ──────────────────────────────────────────────────────────

echo "=== Cleaning previous installation ==="
rm -rf "$TC_HOME"

echo "=== Extracting TeamCity distribution ==="
mkdir -p "$TC_HOME"
tar xzf "$TC_DIST" -C "$TC_HOME" --strip-components=1

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
cat > "$TEAMCITY_DATA_PATH/config/internal.properties" <<PROPS
teamcity.licenseAgreement.accepted=true
teamcity.ai.mcp.tools.enabled=${TC_MCP_TOOLS}
teamcity.ai.mcp.resources.enabled=${TC_MCP_RESOURCES}
PROPS

# ── Start server ─────────────────────────────────────────────────────────────

echo "=== Starting TeamCity server ==="
export TEAMCITY_SERVER_OPTS="-Dteamcity.startup.maintenance=false -Dteamcity.server.port=${TC_PORT}"
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
echo "TC_SERVER_URL=${TC_BASE_URL}"
echo "TC_SERVER_TOKEN=${ACCESS_TOKEN}"
echo "TC_SERVER_RESTRICTED_TOKEN=${RESTRICTED_ACCESS_TOKEN}"

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
EOF
echo "Connection parameters written to ${ENV_FILE}"
