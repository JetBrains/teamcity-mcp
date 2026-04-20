package jetbrains.buildServer.ai.mcp.tests.smoke

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Smoke tests for the MCP plugin — verifies the protocol works end-to-end,
 * independently of any specific tool implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpSmokeTest : McpIntegrationTestBase() {

    // -----------------------------------------------------------------------
    // Session lifecycle
    // -----------------------------------------------------------------------

    @Test
    fun `server is reachable and MCP initializes`() {
        mcpClient().use { client ->
            val session = client.initialize()
            Assertions.assertNotNull(session.sessionId, "Session ID must be non-null")
            Assertions.assertTrue(session.sessionId.isNotBlank(), "Session ID must be non-blank")
            println("  ✓ Initialized session: ${session.sessionId}")
            session.close()
        }
    }

    @Test
    fun `initialize returns valid JSON with protocolVersion and capabilities`() {
        mcpClient().use { client ->
            // Send initialize manually to inspect the JSON response body
            val http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()

            val body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
            val rb = HttpRequest.newBuilder()
                .uri(URI.create(serverConfig.mcpUrl))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", "Bearer ${serverConfig.bearerToken}")
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "2025-11-25")

            val response = http.send(rb.build(), HttpResponse.BodyHandlers.ofString())
            Assertions.assertEquals(200, response.statusCode(), "Initialize must return 200")

            val sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null)
            Assertions.assertNotNull(sessionId, "Must return Mcp-Session-Id header")

            val responseBody = response.body()
            Assertions.assertTrue(responseBody.isNotBlank(), "Initialize must return non-empty JSON body")

            val json = Json.parseToJsonElement(responseBody).jsonObject
            Assertions.assertTrue(json.containsKey("result"), "Response must contain 'result' field")

            val result = json["result"]!!.jsonObject
            Assertions.assertTrue(result.containsKey("protocolVersion"), "Result must contain protocolVersion")
            Assertions.assertTrue(result.containsKey("capabilities"), "Result must contain capabilities")
            Assertions.assertTrue(result.containsKey("serverInfo"), "Result must contain serverInfo")

            val protocolVersion = result["protocolVersion"]?.jsonPrimitive?.content
            Assertions.assertNotNull(protocolVersion, "protocolVersion must be non-null")
            Assertions.assertTrue(protocolVersion!!.isNotBlank(), "protocolVersion must be non-blank")

            println("  ✓ Initialize returned valid JSON with protocolVersion=$protocolVersion, sessionId=$sessionId")

            // Cleanup: delete the session
            client.rawDelete(sessionId!!)
        }
    }

    @Test
    fun `sequential initializations produce unique session IDs`() {
        mcpClient().use { client ->
            val session1 = client.initialize()
            val session2 = client.initialize()
            try {
                Assertions.assertNotEquals(
                    session1.sessionId, session2.sessionId,
                    "Each initialize must return a distinct session ID"
                )
                println("  ✓ Session 1: ${session1.sessionId}")
                println("  ✓ Session 2: ${session2.sessionId}")
            } finally {
                session1.close()
                session2.close()
            }
        }
    }

    @Test
    fun `multiple concurrent sessions can be created`() {
        val clients = List(5) { mcpClient() }
        try {
            val sessions = clients.map { it.initialize() }
            val ids = sessions.map { it.sessionId }.toSet()
            Assertions.assertEquals(5, ids.size, "All sessions must have unique IDs")
            println("  ✓ Created ${ids.size} unique concurrent sessions")
            sessions.forEach { session ->
                try { session.close() } catch (_: Exception) {}
            }
        } finally {
            clients.forEach { it.close() }
        }
    }

    @Test
    fun `session can be properly terminated`() {
        mcpClient().use { client ->
            val session = client.initialize()
            session.close()
            println("  ✓ Session ${session.sessionId} terminated cleanly")
        }
    }

    // -----------------------------------------------------------------------
    // Tools endpoint (protocol-level, not tool-specific)
    // -----------------------------------------------------------------------

    @Test
    fun `tools list is non-empty`() {
        mcpClient().use { client ->
            client.withSession {
                val tools = listTools()
                Assertions.assertFalse(tools.isEmpty(), "Server must expose at least one tool")
                println("  ✓ Tool count: ${tools.size}")
            }
        }
    }

    @Test
    fun `tools list is idempotent`() {
        mcpClient().use { client ->
            client.withSession {
                val first = listTools().map { it.name }.sorted()
                val second = listTools().map { it.name }.sorted()
                Assertions.assertEquals(first, second, "tools/list must return the same result on every call")
                println("  ✓ Consistent tool list: $first")
            }
        }
    }

    @Test
    fun `concurrent tools-list calls in same session all succeed`() {
        mcpClient().use { client ->
            client.withSession {
                val futures = (1..5).map {
                    CompletableFuture.supplyAsync { listTools() }
                }
                val results = futures.map { it.get(30, TimeUnit.SECONDS) }
                Assertions.assertTrue(
                    results.all { it.isNotEmpty() },
                    "All concurrent tools/list calls must return non-empty results"
                )
                println("  ✓ ${results.size} concurrent calls, each returned ${results.first().size} tool(s)")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Tool-specific tests: introduce_yourself
    // -----------------------------------------------------------------------

    @Test
    fun `tools list contains introduce_yourself tool`() {
        mcpClient().use { client ->
            client.withSession {
                val tools = listTools()
                val names = tools.map { it.name }
                Assertions.assertTrue("introduce_yourself" in names, "tools/list must include 'introduce_yourself', got: $names")
                println("  ✓ introduce_yourself tool found")
            }
        }
    }

    @Test
    fun `introduce_yourself tool returns greeting with name`() {
        mcpClient().use { client ->
            client.withSession {
                val result = callTool("introduce_yourself", mapOf("name" to "Claude"))
                Assertions.assertFalse(result.isError, "introduce_yourself should succeed, got: ${result.content}")
                Assertions.assertTrue(
                    result.content.any { it.text.contains("Claude") },
                    "introduce_yourself should mention the name, got: ${result.content}"
                )
                println("  ✓ introduce_yourself returned: ${result.content.firstOrNull()?.text}")
            }
        }
    }

    @Test
    fun `introduce_yourself tool without name uses fallback`() {
        mcpClient().use { client ->
            client.withSession {
                val result = callTool("introduce_yourself", emptyMap())
                Assertions.assertFalse(result.isError, "introduce_yourself without name should succeed")
                Assertions.assertTrue(
                    result.content.any { it.text.contains("stranger") },
                    "introduce_yourself without name should use 'stranger', got: ${result.content}"
                )
                println("  ✓ introduce_yourself fallback: ${result.content.firstOrNull()?.text}")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Tool-specific tests: edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `calling unknown tool returns error`() {
        mcpClient().use { client ->
            client.withSession {
                val result = callTool("nonexistent_tool", emptyMap())
                Assertions.assertTrue(result.isError, "Unknown tool must return error")
                println("  ✓ unknown tool → error: ${result.content.firstOrNull()?.text}")
            }
        }
    }

    @Test
    fun `each tool has a description and inputSchema`() {
        mcpClient().use { client ->
            client.withSession {
                val tools = listTools()
                tools.forEach { tool ->
                    Assertions.assertTrue(tool.name.isNotBlank(), "Tool name must be non-blank")
                    Assertions.assertTrue(tool.description.isNotBlank(), "Tool '${tool.name}' description must be non-blank")
                    Assertions.assertNotNull(tool.inputSchema, "Tool '${tool.name}' must have inputSchema")
                }
                println("  ✓ All ${tools.size} tools have name, description, and inputSchema")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Protocol error cases
    // -----------------------------------------------------------------------

    @Test
    fun `POST with unknown session ID creates session`() {
        val unknownSessionId = "00000000-0000-0000-0000-000000000000"
        mcpClient().use { client ->
            val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
            val status = client.rawPost(
                sessionId = unknownSessionId,
                body = body
            )
            Assertions.assertEquals(200, status, "Unknown session ID must create session and return 200")
            println("  ✓ Unknown session ID → session created ($status)")
            // Cleanup: delete the auto-created session
            client.rawDelete(unknownSessionId)
        }
    }

    @Test
    fun `POST after session deletion creates new session`() {
        mcpClient().use { client ->
            val session = client.initialize()
            val sessionId = session.sessionId
            session.close() // sends DELETE
            Thread.sleep(200) // let the server process the DELETE

            val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
            val status = client.rawPost(sessionId = sessionId, body = body)
            Assertions.assertEquals(200, status, "POST after deletion must create new session and return 200")
            println("  ✓ Deleted session $sessionId → recreated ($status)")
            // Cleanup: delete the auto-created session
            client.rawDelete(sessionId)
        }
    }

    @Test
    fun `GET returns 405 Method Not Allowed`() {
        mcpClient().use { client ->
            val status = client.rawGet(sessionId = null)
            Assertions.assertEquals(405, status, "GET must yield HTTP 405 Method Not Allowed")
            println("  ✓ GET → $status")
        }
    }

}
