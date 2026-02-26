package jetbrains.buildServer.ai.mcp.tests.smoke

import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import jetbrains.buildServer.ai.mcp.SdkClientIntegrationTestBase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Smoke tests using the official MCP Kotlin SDK client.
 *
 * These mirror key scenarios from [McpSmokeTest] but use [io.modelcontextprotocol.kotlin.sdk.client.Client]
 * instead of the custom [jetbrains.buildServer.ai.mcp.framework.TestMcpClient], validating that
 * the server is compatible with the reference client implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SdkClientSmokeTest : SdkClientIntegrationTestBase() {

    @Test
    fun `SDK client initializes and receives server capabilities`() = runBlocking {
        val client = createSdkClient()
        try {
            Assertions.assertNotNull(client.serverCapabilities, "serverCapabilities must be non-null after connect")
            Assertions.assertNotNull(client.serverVersion, "serverVersion must be non-null after connect")
            println("  ✓ SDK client initialized — server: ${client.serverVersion}")
        } finally {
            client.close()
        }
    }

    @Test
    fun `SDK client can list tools`() = runBlocking {
        val client = createSdkClient()
        try {
            val result = client.listTools()
            Assertions.assertFalse(result.tools.isEmpty(), "Server must expose at least one tool")
            println("  ✓ SDK client listed ${result.tools.size} tool(s)")
        } finally {
            client.close()
        }
    }

    @Test
    fun `SDK client tools list is idempotent`() = runBlocking {
        val client = createSdkClient()
        try {
            val first = client.listTools().tools.map { it.name }.sorted()
            val second = client.listTools().tools.map { it.name }.sorted()
            Assertions.assertEquals(first, second, "tools/list must return the same result on every call")
            println("  ✓ Consistent tool list: $first")
        } finally {
            client.close()
        }
    }

    @Test
    fun `SDK client can call a tool`() = runBlocking {
        val client = createSdkClient()
        try {
            val tools = client.listTools().tools
            Assertions.assertFalse(tools.isEmpty(), "Need at least one tool to call")

            val toolName = tools.first().name
            val result = client.callTool(toolName, emptyMap())
            Assertions.assertNotNull(result, "callTool must return a non-null result")
            println("  ✓ SDK client called tool '$toolName' — ${result.content.size} content block(s)")
        } finally {
            client.close()
        }
    }

    @Test
    fun `SDK client can ping server`() = runBlocking {
        val client = createSdkClient()
        try {
            client.ping()
            println("  ✓ SDK client ping succeeded")
        } finally {
            client.close()
        }
    }

    @Test
    fun `SDK client session terminates cleanly`() = runBlocking {
        val client = createSdkClient()
        val transport = client.transport as StreamableHttpClientTransport
        val sessionId = transport.sessionId
        Assertions.assertNotNull(sessionId, "Session ID must be assigned after connect")

        client.close()

        // Verify the session is gone
        Thread.sleep(200)
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        val status = mcpClient().use { probe -> probe.rawPost(sessionId = sessionId!!, body = body) }
        Assertions.assertEquals(404, status, "Closed SDK session must yield HTTP 404")
        println("  ✓ SDK session $sessionId terminated and confirmed gone")
    }

    @Test
    fun `sequential SDK sessions produce unique sessions`() = runBlocking {
        val client1 = createSdkClient()
        val client2 = createSdkClient()
        try {
            val id1 = (client1.transport as StreamableHttpClientTransport).sessionId
            val id2 = (client2.transport as StreamableHttpClientTransport).sessionId
            Assertions.assertNotNull(id1)
            Assertions.assertNotNull(id2)
            Assertions.assertNotEquals(id1, id2, "Each SDK client must get a unique session ID")
            println("  ✓ Session 1: $id1")
            println("  ✓ Session 2: $id2")
        } finally {
            client1.close()
            client2.close()
        }
    }

    @Test
    fun `multiple concurrent SDK sessions work independently`() {
        val executor = Executors.newFixedThreadPool(5)
        try {
            val futures = (1..5).map {
                CompletableFuture.supplyAsync({
                    runBlocking {
                        val client = createSdkClient()
                        try {
                            val tools = client.listTools().tools
                            Assertions.assertTrue(tools.isNotEmpty(), "Each session must list tools successfully")
                            (client.transport as StreamableHttpClientTransport).sessionId!!
                        } finally {
                            client.close()
                        }
                    }
                }, executor)
            }
            val sessionIds = futures.map { it.get(30, TimeUnit.SECONDS) }.toSet()
            Assertions.assertEquals(5, sessionIds.size, "All 5 concurrent sessions must have unique IDs")
            println("  ✓ 5 concurrent SDK sessions, all unique: $sessionIds")
        } finally {
            executor.shutdown()
        }
    }
}