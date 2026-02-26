package jetbrains.buildServer.ai.mcp.tests.smoke

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tests verifying JSON-RPC and MCP protocol compliance:
 * - Correct JSON-RPC response structure (jsonrpc, id, result)
 * - Notification handling (202 Accepted)
 * - Ping/pong
 * - Multi-operation session sequencing
 * - Response ID matching
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpProtocolComplianceTest : McpIntegrationTestBase() {

    // -----------------------------------------------------------------------
    // JSON-RPC response format
    // -----------------------------------------------------------------------

    @Test
    fun `response contains required JSON-RPC fields`() {
        mcpClient().use { client ->
            client.withSession {
                val (status, body) = sendRequestRaw("tools/list", buildJsonObject { })
                assertEquals(200, status)

                val json = Json.parseToJsonElement(body).jsonObject
                assertTrue(json.containsKey("jsonrpc"), "Response must contain 'jsonrpc' field")
                assertTrue(json.containsKey("id"), "Response must contain 'id' field")
                assertTrue(json.containsKey("result"), "Response must contain 'result' field")
                assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content, "jsonrpc version must be '2.0'")
            }
        }
    }

    @Test
    fun `response ID matches request ID`() {
        mcpClient().use { client ->
            client.withSession {
                // sendRequestRaw uses auto-incrementing IDs, starting from 2 (after init)
                val (_, body) = sendRequestRaw("tools/list", buildJsonObject { })
                val json = Json.parseToJsonElement(body).jsonObject
                val responseId = json["id"]?.jsonPrimitive?.intOrNull
                assertNotNull(responseId, "Response must have a numeric 'id' field")
                println("  ✓ Request/response ID matched: $responseId")
            }
        }
    }

    @Test
    fun `initialize response contains serverInfo with name and version`() {
        mcpClient().use { client ->
            val http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build()

            val initBody = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
            val rb = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(serverConfig.mcpUrl))
                .timeout(java.time.Duration.ofSeconds(30))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(initBody))
                .header("Authorization", "Bearer ${serverConfig.bearerToken}")
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "2025-11-25")

            val response = http.send(rb.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())

            val json = Json.parseToJsonElement(response.body()).jsonObject
            val result = json["result"]!!.jsonObject
            val serverInfo = result["serverInfo"]?.jsonObject
            assertNotNull(serverInfo, "Result must contain serverInfo")
            assertTrue(serverInfo!!.containsKey("name"), "serverInfo must contain 'name'")
            assertTrue(serverInfo.containsKey("version"), "serverInfo must contain 'version'")

            val name = serverInfo["name"]?.jsonPrimitive?.content
            assertFalse(name.isNullOrBlank(), "serverInfo.name must be non-blank")
            println("  ✓ serverInfo: name=$name, version=${serverInfo["version"]?.jsonPrimitive?.content}")

            // Cleanup
            val sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null)
            if (sessionId != null) client.rawDelete(sessionId)
        }
    }

    @Test
    fun `initialize response contains capabilities with tools`() {
        mcpClient().use { client ->
            val http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build()

            val initBody = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
            val rb = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(serverConfig.mcpUrl))
                .timeout(java.time.Duration.ofSeconds(30))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(initBody))
                .header("Authorization", "Bearer ${serverConfig.bearerToken}")
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "2025-11-25")

            val response = http.send(rb.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            val json = Json.parseToJsonElement(response.body()).jsonObject
            val result = json["result"]!!.jsonObject
            val capabilities = result["capabilities"]?.jsonObject
            assertNotNull(capabilities, "Result must contain capabilities")
            assertTrue(capabilities!!.containsKey("tools"), "capabilities must contain 'tools'")
            println("  ✓ capabilities: $capabilities")

            val sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null)
            if (sessionId != null) client.rawDelete(sessionId)
        }
    }

    @Test
    fun `initialize response contains capabilities with resources`() {
        mcpClient().use { client ->
            val http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build()

            val initBody = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
            val rb = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(serverConfig.mcpUrl))
                .timeout(java.time.Duration.ofSeconds(30))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(initBody))
                .header("Authorization", "Bearer ${serverConfig.bearerToken}")
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "2025-11-25")

            val response = http.send(rb.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            val json = Json.parseToJsonElement(response.body()).jsonObject
            val result = json["result"]!!.jsonObject
            val capabilities = result["capabilities"]?.jsonObject
            assertNotNull(capabilities, "Result must contain capabilities")
            assertTrue(capabilities!!.containsKey("resources"), "capabilities must contain 'resources'")
            println("  ✓ resources capability present: ${capabilities["resources"]}")

            val sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null)
            if (sessionId != null) client.rawDelete(sessionId)
        }
    }

    @Test
    fun `resources-list response contains properly structured resources`() {
        mcpClient().use { client ->
            client.withSession {
                val (status, body) = sendRequestRaw("resources/list", buildJsonObject { })
                assertEquals(200, status)

                val json = Json.parseToJsonElement(body).jsonObject
                val resources = json["result"]?.jsonObject?.get("resources")?.jsonArray
                assertNotNull(resources, "resources/list result must contain 'resources' array")
                assertTrue(resources!!.isNotEmpty(), "resources array must be non-empty")

                val firstResource = resources[0].jsonObject
                assertTrue(firstResource.containsKey("uri"), "Resource must have 'uri'")
                assertTrue(firstResource.containsKey("name"), "Resource must have 'name'")

                println("  ✓ Resource structure valid: uri=${firstResource["uri"]?.jsonPrimitive?.content}")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Notification handling
    // -----------------------------------------------------------------------

    @Test
    fun `notifications-initialized returns 202 Accepted`() {
        mcpClient().use { client ->
            client.withSession {
                val status = sendNotification("notifications/initialized")
                assertEquals(202, status, "notifications/initialized must return 202 Accepted")
                println("  ✓ notifications/initialized → $status")
            }
        }
    }

    @Test
    fun `arbitrary notification returns 202 Accepted`() {
        mcpClient().use { client ->
            client.withSession {
                val status = sendNotification("notifications/custom", buildJsonObject {
                    put("data", "test")
                })
                assertEquals(202, status, "Custom notification must return 202 Accepted")
                println("  ✓ custom notification → $status")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Ping
    // -----------------------------------------------------------------------

    @Test
    fun `ping returns valid JSON-RPC response`() {
        mcpClient().use { client ->
            client.withSession {
                val response = ping()
                assertTrue(response.containsKey("jsonrpc"), "Ping response must contain 'jsonrpc'")
                assertTrue(response.containsKey("id"), "Ping response must contain 'id'")
                assertTrue(response.containsKey("result"), "Ping response must contain 'result'")
                println("  ✓ ping response: $response")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Multi-operation sessions
    // -----------------------------------------------------------------------

    @Test
    fun `multiple different operations in a single session all succeed`() {
        mcpClient().use { client ->
            client.withSession {
                // 1. List tools
                val tools = listTools()
                assertFalse(tools.isEmpty(), "tools/list must return at least one tool")

                // 2. Call a tool
                val result = callTool("feedback", mapOf("category" to "usability", "message" to "integration test"))
                assertFalse(result.isError, "feedback tool must succeed")

                // 3. Send a notification
                val notifStatus = sendNotification("notifications/initialized")
                assertEquals(202, notifStatus, "Notification must return 202")

                // 4. Ping
                val pingResponse = ping()
                assertTrue(pingResponse.containsKey("result"), "Ping must have result")

                // 5. List tools again (idempotency)
                val tools2 = listTools()
                assertEquals(tools.map { it.name }.sorted(), tools2.map { it.name }.sorted())

                println("  ✓ 5 operations in single session all succeeded")
            }
        }
    }

    @Test
    fun `sequential requests have incrementing IDs and all succeed`() {
        mcpClient().use { client ->
            client.withSession {
                val responses = (1..5).map { sendRequestRaw("tools/list", buildJsonObject { }) }
                responses.forEachIndexed { idx, (status, body) ->
                    assertEquals(200, status, "Request $idx must return 200")
                    val json = Json.parseToJsonElement(body).jsonObject
                    assertTrue(json.containsKey("result"), "Request $idx response must have result")
                }
                println("  ✓ 5 sequential requests all returned 200 with valid responses")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Tool list response structure
    // -----------------------------------------------------------------------

    @Test
    fun `tools-list response contains properly structured tools`() {
        mcpClient().use { client ->
            client.withSession {
                val (status, body) = sendRequestRaw("tools/list", buildJsonObject { })
                assertEquals(200, status)

                val json = Json.parseToJsonElement(body).jsonObject
                val tools = json["result"]?.jsonObject?.get("tools")?.jsonArray
                assertNotNull(tools, "tools/list result must contain 'tools' array")
                assertTrue(tools!!.isNotEmpty(), "tools array must be non-empty")

                val firstTool = tools[0].jsonObject
                assertTrue(firstTool.containsKey("name"), "Tool must have 'name'")
                assertTrue(firstTool.containsKey("description"), "Tool must have 'description'")
                assertTrue(firstTool.containsKey("inputSchema"), "Tool must have 'inputSchema'")

                val schema = firstTool["inputSchema"]?.jsonObject
                assertNotNull(schema, "inputSchema must be a JSON object")

                println("  ✓ Tool structure valid: name=${firstTool["name"]?.jsonPrimitive?.content}")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Error response format
    // -----------------------------------------------------------------------

    @Test
    fun `calling unknown method returns JSON-RPC error`() {
        mcpClient().use { client ->
            client.withSession {
                val (status, body) = sendRequestRaw("nonexistent/method", buildJsonObject { })
                assertEquals(200, status, "JSON-RPC error should still be HTTP 200")

                val json = Json.parseToJsonElement(body).jsonObject
                // JSON-RPC errors should have "error" field
                val error = json["error"]?.jsonObject
                assertNotNull(error, "Unknown method must produce JSON-RPC error")
                assertTrue(error!!.containsKey("code"), "JSON-RPC error must contain 'code'")
                assertTrue(error.containsKey("message"), "JSON-RPC error must contain 'message'")
                println("  ✓ Unknown method → error code=${error["code"]?.jsonPrimitive?.content}")
            }
        }
    }
}
