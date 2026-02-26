    package jetbrains.buildServer.ai.mcp.framework

import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

// ---------------------------------------------------------------------------
// Exception
// ---------------------------------------------------------------------------

class McpException(
    message: String,
    val statusCode: Int = 0,
    val body: String = ""
) : RuntimeException("$message (status=$statusCode, body=$body)")


// ---------------------------------------------------------------------------
// TC Server Config
// ---------------------------------------------------------------------------

/**
 * Connection details for a TeamCity server used in integration tests.
 *
 * @param baseUrl       Base URL, e.g. "http://localhost:8111"
 * @param bearerToken   Permanent TC token (eyJ...) for Bearer auth on /app/mcp
 */
data class TcServerConfig(
    val baseUrl: String,
    val bearerToken: String,
) {
    val mcpUrl: String get() = "$baseUrl/app/mcp"

    override fun toString(): String =
        "TcServerConfig(baseUrl=$baseUrl, bearer=${bearerToken.take(12)}...)"
}


// ---------------------------------------------------------------------------
// TestMcpClient
// ---------------------------------------------------------------------------

/**
 * HTTP client for the MCP Streamable HTTP protocol (version 2025-11-25, JSON-only mode).
 *
 * Auth: `Authorization: Bearer {token}` on every request (no CSRF, no cookies needed).
 *
 * MCP session lifecycle (JSON-only, spec-compliant):
 *   - POST  /app/mcp (no Mcp-Session-Id)  → initialize → JSON body with InitializeResult + Mcp-Session-Id header
 *   - POST  /app/mcp + Mcp-Session-Id     → request → JSON body with response
 *   - POST  /app/mcp + Mcp-Session-Id     → notification → 202 Accepted
 *   - GET   /app/mcp                      → 405 Method Not Allowed
 *   - DELETE /app/mcp + Mcp-Session-Id    → close session
 */
class TestMcpClient(private val serverConfig: TcServerConfig) : AutoCloseable {

    // ------------------------------------------------------------------
    // Public data types
    // ------------------------------------------------------------------

    data class McpTool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject
    )

    data class ToolContent(val type: String, val text: String)

    data class ToolResult(
        val content: List<ToolContent>,
        val isError: Boolean = false
    )

    data class McpResource(
        val uri: String,
        val name: String,
        val description: String,
        val mimeType: String
    )

    data class ResourceContent(
        val uri: String,
        val mimeType: String,
        val text: String
    )

    // ------------------------------------------------------------------
    // HTTP client (shared, stateless)
    // ------------------------------------------------------------------

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    // ------------------------------------------------------------------
    // Auth headers helper
    // ------------------------------------------------------------------

    private fun authHeaders(sessionId: String? = null): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${serverConfig.bearerToken}")
        put("Accept", "application/json, text/event-stream")
        put("Content-Type", "application/json")
        put("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
        if (sessionId != null) put("Mcp-Session-Id", sessionId)
    }

    // ------------------------------------------------------------------
    // initialize()
    // ------------------------------------------------------------------

    /**
     * Sends the MCP `initialize` request and returns a new [McpSession].
     *
     * Reads the JSON response body containing InitializeResult and extracts
     * the session ID from the Mcp-Session-Id header.
     *
     * The caller is responsible for closing the session.
     */
    fun initialize(): McpSession {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(serverConfig.mcpUrl))
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(initializeBody()))
        authHeaders().forEach { (k, v) -> requestBuilder.header(k, v) }

        val response = http.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw McpException("initialize failed", response.statusCode(), response.body())
        }

        val sessionId = response.headers().firstValue("Mcp-Session-Id").orElseThrow {
            McpException("Server did not return Mcp-Session-Id header", response.statusCode(), response.body())
        }

        // Validate that the response body contains valid JSON-RPC response
        val body = response.body()
        if (body.isNotBlank()) {
            try {
                val json = Json.parseToJsonElement(body).jsonObject
                // Should have "jsonrpc", "id", and "result" fields
                require(json.containsKey("result")) {
                    "Initialize response must contain 'result' field, got: $body"
                }
            } catch (e: Exception) {
                throw McpException("Invalid initialize response body: ${e.message}", response.statusCode(), body)
            }
        }

        return McpSession(sessionId)
    }

    /**
     * Convenience wrapper: opens a session, runs [block], then closes it.
     */
    fun withSession(block: McpSession.() -> Unit) {
        initialize().use { session -> session.block() }
    }

    // ------------------------------------------------------------------
    // Raw protocol-level HTTP (for error-case testing)
    // ------------------------------------------------------------------

    /**
     * Sends the MCP `initialize` POST and returns the server-assigned session ID
     * **without** validating the response body.
     *
     * Useful for testing cleanup of sessions.
     */
    fun rawInit(): String {
        val rb = HttpRequest.newBuilder()
            .uri(URI.create(serverConfig.mcpUrl))
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(initializeBody()))
        authHeaders().forEach { (k, v) -> rb.header(k, v) }
        val response = http.send(rb.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw McpException("rawInit failed", response.statusCode())
        return response.headers().firstValue("Mcp-Session-Id").orElseThrow {
            McpException("rawInit: server did not return Mcp-Session-Id header", response.statusCode())
        }
    }

    /**
     * Sends a POST to /app/mcp and returns the HTTP response status code.
     * Does not parse the response body — intended for testing error cases.
     *
     * @param sessionId  included as `Mcp-Session-Id` header when non-null
     * @param body       raw request body (must be valid JSON-RPC)
     */
    fun rawPost(sessionId: String? = null, body: String): Int {
        val rb = HttpRequest.newBuilder()
            .uri(URI.create(serverConfig.mcpUrl))
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body))
        authHeaders(sessionId).forEach { (k, v) -> rb.header(k, v) }
        return http.send(rb.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    /**
     * Sends a DELETE to /app/mcp for [sessionId] and returns the HTTP status code.
     */
    fun rawDelete(sessionId: String): Int {
        val rb = HttpRequest.newBuilder()
            .uri(URI.create(serverConfig.mcpUrl))
            .timeout(Duration.ofSeconds(15))
            .method("DELETE", HttpRequest.BodyPublishers.noBody())
        authHeaders(sessionId).forEach { (k, v) -> rb.header(k, v) }
        return http.send(rb.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    /**
     * Sends a POST to /app/mcp with fully custom headers and returns the HTTP status code.
     * Intended for protocol-level error-case testing (e.g. wrong Content-Type, missing Accept).
     */
    fun rawPostCustomHeaders(
        body: String,
        headers: Map<String, String>
    ): Int {
        val rb = HttpRequest.newBuilder()
            .uri(URI.create(serverConfig.mcpUrl))
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> rb.header(k, v) }
        return http.send(rb.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    /**
     * Sends a POST to /app/mcp with fully custom headers and returns (statusCode, body).
     * For inspecting error response bodies.
     */
    fun rawPostCustomHeadersWithBody(
        body: String,
        headers: Map<String, String>
    ): Pair<Int, String> {
        val rb = HttpRequest.newBuilder()
            .uri(URI.create(serverConfig.mcpUrl))
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> rb.header(k, v) }
        val response = http.send(rb.build(), HttpResponse.BodyHandlers.ofString())
        return Pair(response.statusCode(), response.body() ?: "")
    }

    /**
     * Sends a DELETE to /app/mcp with fully custom headers and returns the HTTP status code.
     * For testing DELETE error cases.
     */
    fun rawDeleteCustomHeaders(headers: Map<String, String>): Int {
        val rb = HttpRequest.newBuilder()
            .uri(URI.create(serverConfig.mcpUrl))
            .timeout(Duration.ofSeconds(15))
            .method("DELETE", HttpRequest.BodyPublishers.noBody())
        headers.forEach { (k, v) -> rb.header(k, v) }
        return http.send(rb.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    /**
     * Sends a GET to /app/mcp and returns the HTTP response status code.
     *
     * Used for verifying that GET returns 405 Method Not Allowed.
     */
    fun rawGet(sessionId: String? = null): Int {
        val rb = HttpRequest.newBuilder()
            .uri(URI.create(serverConfig.mcpUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json, text/event-stream")
            .header("Authorization", "Bearer ${serverConfig.bearerToken}")
            .header("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
            .GET()
        if (sessionId != null) rb.header("Mcp-Session-Id", sessionId)
        return http.send(rb.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    // ------------------------------------------------------------------
    // AutoCloseable
    // ------------------------------------------------------------------

    override fun close() {
        (http as? java.io.Closeable)?.close()
    }

    // ======================================================================
    // Inner class: McpSession
    // ======================================================================

    /**
     * Represents an active MCP session.
     *
     * Each request is sent as a POST and the response is read as a JSON body
     * from that POST response (JSON-only mode, spec-compliant).
     */
    inner class McpSession(val sessionId: String) : AutoCloseable {

        private val nextId = AtomicInteger(2)

        // ------------------------------------------------------------------
        // tools/list
        // ------------------------------------------------------------------

        /** Lists available MCP tools. */
        fun listTools(): List<McpTool> {
            val response = sendRequest("tools/list", buildJsonObject { })
            val tools = response["result"]?.jsonObject?.get("tools")?.jsonArray ?: return emptyList()
            return tools.map { el ->
                val tool = el.jsonObject
                McpTool(
                    name        = tool["name"]?.jsonPrimitive?.content ?: "",
                    description = tool["description"]?.jsonPrimitive?.content ?: "",
                    inputSchema = tool["inputSchema"]?.jsonObject ?: buildJsonObject { }
                )
            }
        }

        // ------------------------------------------------------------------
        // tools/call
        // ------------------------------------------------------------------

        /** Calls a named MCP tool with the given arguments. */
        fun callTool(name: String, arguments: Map<String, Any?> = emptyMap()): ToolResult {
            val params = buildJsonObject {
                put("name", name)
                put("arguments", mapToJsonObject(arguments))
            }
            val response = sendRequest("tools/call", params)

            (response["error"] as? JsonObject)?.let { error ->
                val message = error["message"]?.jsonPrimitive?.content ?: "Unknown MCP error"
                return ToolResult(listOf(ToolContent("text", message)), isError = true)
            }

            val result = response["result"]?.jsonObject ?: return ToolResult(emptyList())
            val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
            val content = result["content"]?.jsonArray?.map { el ->
                val item = el.jsonObject
                ToolContent(
                    type = item["type"]?.jsonPrimitive?.content ?: "text",
                    text = item["text"]?.jsonPrimitive?.content ?: ""
                )
            } ?: emptyList()
            return ToolResult(content, isError)
        }

        // ------------------------------------------------------------------
        // resources/list
        // ------------------------------------------------------------------

        /** Lists available MCP resources. */
        fun listResources(): List<McpResource> {
            val response = sendRequest("resources/list", buildJsonObject { })
            val resources = response["result"]?.jsonObject?.get("resources")?.jsonArray ?: return emptyList()
            return resources.map { el ->
                val res = el.jsonObject
                McpResource(
                    uri         = res["uri"]?.jsonPrimitive?.content ?: "",
                    name        = res["name"]?.jsonPrimitive?.content ?: "",
                    description = res["description"]?.jsonPrimitive?.content ?: "",
                    mimeType    = res["mimeType"]?.jsonPrimitive?.content ?: ""
                )
            }
        }

        // ------------------------------------------------------------------
        // resources/read
        // ------------------------------------------------------------------

        /** Reads the content of an MCP resource by URI. */
        fun readResource(uri: String): List<ResourceContent> {
            val params = buildJsonObject { put("uri", uri) }
            val response = sendRequest("resources/read", params)

            (response["error"] as? JsonObject)?.let { error ->
                val message = error["message"]?.jsonPrimitive?.content ?: "Unknown MCP error"
                throw McpException("resources/read failed: $message")
            }

            val result = response["result"]?.jsonObject ?: return emptyList()
            val contents = result["contents"]?.jsonArray ?: return emptyList()
            return contents.map { el ->
                val item = el.jsonObject
                ResourceContent(
                    uri      = item["uri"]?.jsonPrimitive?.content ?: "",
                    mimeType = item["mimeType"]?.jsonPrimitive?.content ?: "",
                    text     = item["text"]?.jsonPrimitive?.content ?: ""
                )
            }
        }

        // ------------------------------------------------------------------
        // ping
        // ------------------------------------------------------------------

        /** Sends a JSON-RPC ping request and verifies the response. */
        fun ping(): JsonObject = sendRequest("ping", buildJsonObject { })

        // ------------------------------------------------------------------
        // notifications
        // ------------------------------------------------------------------

        /**
         * Sends a JSON-RPC notification (no "id" field).
         * Returns the HTTP status code — spec says 202 Accepted for notifications.
         */
        fun sendNotification(method: String, params: JsonObject = buildJsonObject { }): Int {
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }.toString()

            val rb = HttpRequest.newBuilder()
                .uri(URI.create(serverConfig.mcpUrl))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
            authHeaders(sessionId).forEach { (k, v) -> rb.header(k, v) }

            return http.send(rb.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
        }

        // ------------------------------------------------------------------
        // Raw request with full response (for JSON-RPC compliance testing)
        // ------------------------------------------------------------------

        /**
         * Sends a JSON-RPC request and returns the raw response body as a string.
         * Unlike [sendRequest], does not validate the response — returns the raw JSON.
         */
        fun sendRequestRaw(method: String, params: JsonObject): Pair<Int, String> {
            val id = nextId.getAndIncrement()

            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString()

            val rb = HttpRequest.newBuilder()
                .uri(URI.create(serverConfig.mcpUrl))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
            authHeaders(sessionId).forEach { (k, v) -> rb.header(k, v) }

            val response = http.send(rb.build(), HttpResponse.BodyHandlers.ofString())
            return Pair(response.statusCode(), response.body() ?: "")
        }

        // ------------------------------------------------------------------
        // close (DELETE)
        // ------------------------------------------------------------------

        override fun close() {
            try {
                val rb = HttpRequest.newBuilder()
                    .uri(URI.create(serverConfig.mcpUrl))
                    .timeout(Duration.ofSeconds(10))
                    .method("DELETE", HttpRequest.BodyPublishers.noBody())
                authHeaders(sessionId).forEach { (k, v) -> rb.header(k, v) }
                http.send(rb.build(), HttpResponse.BodyHandlers.discarding())
            } catch (_: Exception) {
                // Session may already be closed; ignore.
            }
        }

        // ------------------------------------------------------------------
        // Private helpers
        // ------------------------------------------------------------------

        /**
         * Sends a JSON-RPC request via POST and reads the JSON response body.
         *
         * In JSON-only mode, the server returns `application/json` with the
         * JSON-RPC response directly in the body.
         */
        private fun sendRequest(method: String, params: JsonObject): JsonObject {
            val id = nextId.getAndIncrement()

            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString()

            val rb = HttpRequest.newBuilder()
                .uri(URI.create(serverConfig.mcpUrl))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
            authHeaders(sessionId).forEach { (k, v) -> rb.header(k, v) }

            val response = http.send(rb.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                throw McpException("$method expected 200, got ${response.statusCode()}", response.statusCode())
            }

            val responseBody = response.body()
            if (responseBody.isNullOrBlank()) {
                throw McpException("$method: empty response body")
            }

            return try {
                val json = Json.parseToJsonElement(responseBody).jsonObject
                val responseId = (json["id"] as? JsonPrimitive)?.intOrNull
                if (responseId != id) {
                    throw McpException("$method: response id mismatch (expected $id, got $responseId)")
                }
                json
            } catch (e: McpException) {
                throw e
            } catch (e: Exception) {
                throw McpException("$method: failed to parse response body: ${e.message}", body = responseBody)
            }
        }
    }

    // ======================================================================
    // Private helpers
    // ======================================================================

    /** Fixed JSON-RPC initialize body, shared by [initialize] and [rawInit]. */
    private fun initializeBody(): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 1)
        put("method", "initialize")
        put("params", buildJsonObject {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject {
                put("name", "mcp-integration-test")
                put("version", "1.0")
            })
        })
    }.toString()

    companion object {
        private const val MCP_PROTOCOL_VERSION = "2025-11-25"

        /** Converts a [Map] with arbitrary values to a [JsonObject] for tool arguments. */
        fun mapToJsonObject(map: Map<String, Any?>): JsonObject = buildJsonObject {
            map.forEach { (k, v) -> put(k, anyToJsonElement(v)) }
        }

        private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
            null         -> JsonNull
            is Boolean   -> JsonPrimitive(value)
            is Number    -> JsonPrimitive(value)
            is String    -> JsonPrimitive(value)
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") mapToJsonObject(value as Map<String, Any?>)
            is List<*>   -> buildJsonArray { value.forEach { add(anyToJsonElement(it)) } }
            else         -> JsonPrimitive(value.toString())
        }
    }
}
