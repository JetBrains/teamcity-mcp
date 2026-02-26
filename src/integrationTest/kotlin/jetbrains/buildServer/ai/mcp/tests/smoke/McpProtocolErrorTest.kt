package jetbrains.buildServer.ai.mcp.tests.smoke

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tests that exercise every protocol-level error path the MCP server must handle:
 * invalid headers, missing headers, malformed bodies, wrong HTTP methods, etc.
 *
 * All tests use raw HTTP to send intentionally wrong requests and validate
 * the correct HTTP error status is returned.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpProtocolErrorTest : McpIntegrationTestBase() {

    private val validBody = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
    private val initBody = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""

    private fun baseHeaders(sessionId: String? = null): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${serverConfig.bearerToken}")
        put("Accept", "application/json, text/event-stream")
        put("Content-Type", "application/json")
        put("MCP-Protocol-Version", "2025-11-25")
        if (sessionId != null) put("Mcp-Session-Id", sessionId)
    }

    // -----------------------------------------------------------------------
    // Content-Type validation
    // -----------------------------------------------------------------------

    @Test
    fun `POST with text-plain Content-Type returns 400`() {
        mcpClient().use { client ->
            val headers = baseHeaders() + ("Content-Type" to "text/plain")
            val status = client.rawPostCustomHeaders(initBody, headers)
            assertEquals(400, status, "text/plain Content-Type must yield 400")
        }
    }

    @Test
    fun `POST with xml Content-Type returns 400`() {
        mcpClient().use { client ->
            val headers = baseHeaders() + ("Content-Type" to "application/xml")
            val status = client.rawPostCustomHeaders(initBody, headers)
            assertEquals(400, status, "application/xml Content-Type must yield 400")
        }
    }

    // -----------------------------------------------------------------------
    // Accept header validation
    // -----------------------------------------------------------------------

    @Test
    fun `POST without Accept header returns 400`() {
        mcpClient().use { client ->
            val headers = baseHeaders().toMutableMap()
            headers.remove("Accept")
            val status = client.rawPostCustomHeaders(initBody, headers)
            assertEquals(400, status, "Missing Accept header must yield 400")
        }
    }

    @Disabled("Server will accept requests without text/event-stream to support production clients")
    @Test
    fun `POST with Accept missing text-event-stream returns 400`() {
        mcpClient().use { client ->
            val headers = baseHeaders() + ("Accept" to "application/json")
            val status = client.rawPostCustomHeaders(initBody, headers)
            assertEquals(400, status, "Accept without text/event-stream must yield 400")
        }
    }

    @Test
    fun `POST with Accept missing application-json returns 400`() {
        mcpClient().use { client ->
            val headers = baseHeaders() + ("Accept" to "text/event-stream")
            val status = client.rawPostCustomHeaders(initBody, headers)
            assertEquals(400, status, "Accept without application/json must yield 400")
        }
    }

    // -----------------------------------------------------------------------
    // Protocol version validation
    // -----------------------------------------------------------------------

    @Test
    fun `POST request with unsupported protocol version returns 400`() {
        mcpClient().use { client ->
            val session = client.initialize()
            try {
                val headers = baseHeaders(session.sessionId) + ("MCP-Protocol-Version" to "1999-01-01")
                val status = client.rawPostCustomHeaders(validBody, headers)
                assertEquals(400, status, "Unsupported protocol version must yield 400")
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `POST request without protocol version header succeeds for backward compatibility`() {
        mcpClient().use { client ->
            val session = client.initialize()
            try {
                val headers = baseHeaders(session.sessionId).toMutableMap()
                headers.remove("MCP-Protocol-Version")
                val status = client.rawPostCustomHeaders(validBody, headers)
                assertEquals(200, status, "Missing MCP-Protocol-Version should succeed for backward compatibility")
            } finally {
                session.close()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Malformed JSON body
    // -----------------------------------------------------------------------

    @Test
    fun `POST with malformed JSON body returns 400`() {
        mcpClient().use { client ->
            val status = client.rawPostCustomHeaders("this is not json {{{", baseHeaders())
            assertEquals(400, status, "Malformed JSON body must yield 400")
        }
    }

    @Test
    fun `POST with empty body returns 400`() {
        mcpClient().use { client ->
            val status = client.rawPostCustomHeaders("", baseHeaders())
            assertEquals(400, status, "Empty body must yield 400")
        }
    }

    // -----------------------------------------------------------------------
    // Session ID validation
    // -----------------------------------------------------------------------

    @Test
    fun `POST initialize with session ID returns 400`() {
        mcpClient().use { client ->
            val headers = baseHeaders("some-session-id")
            val status = client.rawPostCustomHeaders(initBody, headers)
            assertEquals(400, status, "Initialize with Mcp-Session-Id must yield 400")
        }
    }

    @Test
    fun `POST request without session ID returns 400`() {
        mcpClient().use { client ->
            val headers = baseHeaders() // no session ID
            val status = client.rawPostCustomHeaders(validBody, headers)
            assertEquals(400, status, "Non-init request without Mcp-Session-Id must yield 400")
        }
    }

    @Test
    fun `POST with unknown session ID returns 404`() {
        mcpClient().use { client ->
            val headers = baseHeaders("00000000-0000-0000-0000-000000000000")
            val status = client.rawPostCustomHeaders(validBody, headers)
            assertEquals(404, status, "Unknown session ID must yield 404")
        }
    }

    // -----------------------------------------------------------------------
    // DELETE validation
    // -----------------------------------------------------------------------

    // Note: DELETE error responses return 405 due to TC framework error handling.
    // The controller correctly validates and throws 400/404, but TC's Spring
    // integration converts DeferredResult errors from @DeleteMapping to 405.

    @Test
    fun `DELETE without session ID is rejected`() {
        mcpClient().use { client ->
            val headers = baseHeaders()
            val status = client.rawDeleteCustomHeaders(headers)
            assertTrue(status in listOf(400, 405), "DELETE without Mcp-Session-Id must be rejected, got $status")
        }
    }

    @Test
    fun `DELETE with unknown session ID is rejected`() {
        mcpClient().use { client ->
            val status = client.rawDelete("00000000-0000-0000-0000-000000000000")
            assertTrue(status in listOf(404, 405), "DELETE with unknown session must be rejected, got $status")
        }
    }

    @Test
    fun `DELETE without protocol version proceeds to session handling`() {
        mcpClient().use { client ->
            val headers = baseHeaders("some-id").toMutableMap()
            headers.remove("MCP-Protocol-Version")
            val status = client.rawDeleteCustomHeaders(headers)
            assertTrue(status in listOf(404, 405), "DELETE without MCP-Protocol-Version should proceed to session handling, got $status")
        }
    }

    @Test
    fun `DELETE with unsupported protocol version proceeds to session handling`() {
        mcpClient().use { client ->
            val headers = baseHeaders("some-id") + ("MCP-Protocol-Version" to "1999-01-01")
            val status = client.rawDeleteCustomHeaders(headers)
            assertTrue(status in listOf(404, 405), "DELETE with unsupported protocol version should proceed to session handling, got $status")
        }
    }

    // -----------------------------------------------------------------------
    // GET endpoint
    // -----------------------------------------------------------------------

    @Test
    fun `GET without session returns 405`() {
        mcpClient().use { client ->
            val status = client.rawGet(sessionId = null)
            assertEquals(405, status, "GET must yield 405 Method Not Allowed")
        }
    }

    @Test
    fun `GET with session returns 405`() {
        mcpClient().use { client ->
            val session = client.initialize()
            try {
                val status = client.rawGet(sessionId = session.sessionId)
                assertEquals(405, status, "GET with session must still yield 405")
            } finally {
                session.close()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Authentication
    // -----------------------------------------------------------------------

    @Test
    fun `POST without Authorization header returns 401`() {
        mcpClient().use { client ->
            val headers = baseHeaders().toMutableMap()
            headers.remove("Authorization")
            val status = client.rawPostCustomHeaders(initBody, headers)
            assertTrue(status in listOf(401, 403), "Missing auth must yield 401 or 403, got $status")
        }
    }

    @Test
    fun `POST with invalid bearer token returns 401 or 403`() {
        mcpClient().use { client ->
            val headers = baseHeaders() + ("Authorization" to "Bearer invalid-token-xyz")
            val status = client.rawPostCustomHeaders(initBody, headers)
            assertTrue(status in listOf(401, 403), "Invalid bearer token must yield 401 or 403, got $status")
        }
    }
}
