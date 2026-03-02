package jetbrains.buildServer.ai.mcp.tests.stress

import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import jetbrains.buildServer.ai.mcp.SdkClientIntegrationTestBase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stress test using the official MCP Kotlin SDK client.
 *
 * Opens [SESSION_COUNT] concurrent SDK sessions, each performing [REQUESTS_PER_SESSION]
 * tools/list calls, then closes every session and verifies cleanup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SdkClientStressTest : SdkClientIntegrationTestBase() {

    @Test
    fun `server handles many concurrent SDK client sessions`() {
        val executor = Executors.newCachedThreadPool()
        val closedIds = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()

        println("  Starting $SESSION_COUNT concurrent SDK sessions, $REQUESTS_PER_SESSION requests each …")
        val t0 = System.currentTimeMillis()

        val futures = List(SESSION_COUNT) { idx ->
            CompletableFuture.supplyAsync({
                runSession(idx, closedIds, errors)
            }, executor)
        }

        futures.forEach { it.get(TIMEOUT_SEC, TimeUnit.SECONDS) }
        executor.shutdown()

        val elapsed = System.currentTimeMillis() - t0
        val succeeded = SESSION_COUNT - errors.size
        println("  Finished: $succeeded/$SESSION_COUNT SDK sessions OK in ${elapsed}ms " +
                "(${elapsed / SESSION_COUNT}ms avg)")

        if (errors.isNotEmpty()) {
            errors.forEach { println("  ✗ $it") }
        }
        Assertions.assertEquals(
            0, errors.size,
            "${errors.size} SDK session(s) failed — see output above"
        )

        // Verify every closed session is gone server-side
        verifyAllClosed(closedIds)
    }

    // -------------------------------------------------------------------------

    private fun runSession(idx: Int, closedIds: CopyOnWriteArrayList<String>, errors: CopyOnWriteArrayList<String>) {
        try {
            runBlocking {
                val client = createSdkClient()
                try {
                    // Verify session started: first request must succeed
                    val tools = client.listTools().tools
                    check(tools.isNotEmpty()) {
                        "SDK session[$idx]: tools/list returned empty list"
                    }

                    // Remaining requests
                    repeat(REQUESTS_PER_SESSION - 1) { client.listTools() }

                    val sessionId = (client.transport as StreamableHttpClientTransport).sessionId
                    client.close()
                    if (sessionId != null) closedIds.add(sessionId)
                } catch (e: Exception) {
                    try {
                        client.close()
                    } catch (_: Exception) {
                    }
                    throw e
                }
            }
        } catch (e: Exception) {
            errors.add("sdk-session[$idx]: ${e.message}")
        }
    }

    private fun verifyAllClosed(ids: List<String>) {
        if (ids.isEmpty()) return

        val stillAlive = AtomicInteger()

        mcpClient().use { probe ->
            ids.forEach { id ->
                val status = probe.rawDelete(id)
                if (status == 200) {
                    println("  ✗ SDK session $id still alive after close (DELETE returned 200)")
                    stillAlive.incrementAndGet()
                }
            }
        }

        println("  ✓ ${ids.size - stillAlive.get()}/${ids.size} SDK sessions confirmed gone")
        Assertions.assertEquals(
            0, stillAlive.get(),
            "${stillAlive.get()} SDK session(s) were not cleaned up server-side after DELETE"
        )
    }

    companion object {
        private const val SESSION_COUNT = 20
        private const val REQUESTS_PER_SESSION = 5
        private const val TIMEOUT_SEC = 120L
    }
}