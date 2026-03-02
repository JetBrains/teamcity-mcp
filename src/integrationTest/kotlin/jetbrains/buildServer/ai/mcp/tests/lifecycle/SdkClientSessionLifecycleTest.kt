package jetbrains.buildServer.ai.mcp.tests.lifecycle

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
 * Session lifecycle tests using the official MCP Kotlin SDK client.
 *
 * Mirrors [McpSessionLifecycleTest] termination paths but uses the SDK
 * [io.modelcontextprotocol.kotlin.sdk.client.Client] for session management.
 * Raw HTTP probes via [mcpClient] are still used to verify server-side cleanup.
 *
 * Termination paths covered:
 *  1. SDK client closed before any requests
 *  2. SDK client closed after normal use
 *  3. Concurrent close calls on the same SDK client
 *  4. High-volume rapid SDK session create-and-close cycles (leak regression)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SdkClientSessionLifecycleTest : SdkClientIntegrationTestBase() {

    private val probe = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""

    // -------------------------------------------------------------------------
    // Path 1 & 2: normal close paths
    // -------------------------------------------------------------------------

    @Test
    fun `SDK session closed before any requests is immediately gone`() = runBlocking {
        val client = createSdkClient()
        val sessionId = (client.transport as StreamableHttpClientTransport).sessionId
        Assertions.assertNotNull(sessionId)

        client.close()
        Thread.sleep(200)

        mcpClient().use { probe_client ->
            Assertions.assertTrue(
                probe_client.rawDelete(sessionId!!) != 200,
                "SDK session must be gone immediately after close (no prior requests)"
            )
        }
    }

    @Test
    fun `SDK session closed after normal use is immediately gone`() = runBlocking {
        val client = createSdkClient()
        val sessionId = (client.transport as StreamableHttpClientTransport).sessionId
        Assertions.assertNotNull(sessionId)

        client.listTools()
        client.close()
        Thread.sleep(200)

        mcpClient().use { probe_client ->
            Assertions.assertTrue(
                probe_client.rawDelete(sessionId!!) != 200,
                "SDK session must be gone immediately after close (after normal use)"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Path 3: SDK session persists until closed
    // -------------------------------------------------------------------------

    @Test
    fun `SDK session persists while client is alive`() = runBlocking {
        val client = createSdkClient()
        val sessionId = (client.transport as StreamableHttpClientTransport).sessionId
        Assertions.assertNotNull(sessionId)

        // Session should be alive — raw POST should succeed
        mcpClient().use { probeClient ->
            val statusWhileAlive = probeClient.rawPost(sessionId!!, probe)
            Assertions.assertTrue(
                statusWhileAlive in 200..202,
                "POST to live SDK session must be accepted (got $statusWhileAlive)"
            )
        }

        // Second request through SDK still works
        val tools = client.listTools().tools
        Assertions.assertTrue(tools.isNotEmpty(), "listTools must succeed while session is alive")

        // Now close and verify cleanup
        client.close()
        Thread.sleep(200)

        mcpClient().use { probeClient ->
            Assertions.assertTrue(
                probeClient.rawDelete(sessionId!!) != 200,
                "SDK session must be gone after close"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Path 4: concurrent close calls
    // -------------------------------------------------------------------------

    @Test
    fun `concurrent close calls on the same SDK client are handled safely`() = runBlocking {
        val client = createSdkClient()
        val sessionId = (client.transport as StreamableHttpClientTransport).sessionId
        Assertions.assertNotNull(sessionId)

        val executor = Executors.newFixedThreadPool(5)
        val futures = (1..5).map {
            CompletableFuture.supplyAsync({
                runBlocking {
                    try {
                        client.close()
                        "ok"
                    } catch (e: Exception) {
                        "error: ${e.message}"
                    }
                }
            }, executor)
        }
        futures.forEach { it.get(15, TimeUnit.SECONDS) }
        executor.shutdown()

        Thread.sleep(200)
        mcpClient().use { probeClient ->
            Assertions.assertTrue(
                probeClient.rawDelete(sessionId!!) != 200,
                "SDK session must be gone after concurrent close calls"
            )
        }
        println("  ✓ Concurrent SDK close calls handled safely")
    }

    // -------------------------------------------------------------------------
    // Path 5: rapid create-and-close cycles (leak regression)
    // -------------------------------------------------------------------------

    @Test
    fun `rapid SDK session create-and-close cycles leave no leaked sessions`() {
        val count = 50
        val executor = Executors.newCachedThreadPool()

        val sessionIds = (1..count).map {
            CompletableFuture.supplyAsync({
                runBlocking {
                    val client = createSdkClient()
                    val sessionId = (client.transport as StreamableHttpClientTransport).sessionId!!
                    client.close()
                    sessionId
                }
            }, executor)
        }.map { it.get(60, TimeUnit.SECONDS) }

        executor.shutdown()

        mcpClient().use { probeClient ->
            val leaked = sessionIds.filter { probeClient.rawDelete(it) == 200 }
            Assertions.assertTrue(
                leaked.isEmpty(),
                "${leaked.size}/$count SDK sessions were not cleaned up after close: $leaked"
            )
        }
        println("  ✓ $count rapid SDK create-close cycles, no leaks")
    }
}