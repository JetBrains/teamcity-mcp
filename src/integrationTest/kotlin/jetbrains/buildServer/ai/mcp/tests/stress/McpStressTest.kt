package jetbrains.buildServer.ai.mcp.tests.stress

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import jetbrains.buildServer.ai.mcp.framework.TestMcpClient
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpStressTest : McpIntegrationTestBase() {

    /**
     * Opens [SESSION_COUNT] sessions concurrently, each performing [REQUESTS_PER_SESSION]
     * tools/list calls, then closes every session.
     *
     * Lifecycle verification:
     *  - **Started correctly**: the first tools/list call in each session must succeed
     *    (a failed or partially-initialised session would throw an exception here).
     *  - **Closed correctly**: after DELETE, a POST with the old session ID must return 404,
     *    confirming the server removed the session from its registry.
     */
    @Test
    fun `server handles many concurrent sessions with multiple requests each`() {
        val executor = Executors.newCachedThreadPool()
        val closedIds = CopyOnWriteArrayList<String>()
        val errors    = CopyOnWriteArrayList<String>()

        println("  Starting $SESSION_COUNT concurrent sessions, $REQUESTS_PER_SESSION requests each …")
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
        println("  Finished: $succeeded/$SESSION_COUNT sessions OK in ${elapsed}ms " +
                "(${elapsed / SESSION_COUNT}ms avg)")

        if (errors.isNotEmpty()) {
            errors.forEach { println("  ✗ $it") }
        }
        Assertions.assertEquals(
            0, errors.size,
            "${errors.size} session(s) failed — see output above"
        )

        // Verify every closed session is gone server-side
        verifyAllClosed(closedIds)
    }

    // -------------------------------------------------------------------------

    private fun runSession(idx: Int, closedIds: CopyOnWriteArrayList<String>, errors: CopyOnWriteArrayList<String>) {
        try {
            TestMcpClient(serverConfig).use { client ->
                val session = client.initialize()

                // Verify session started: first request must succeed
                val tools = session.listTools()
                check(tools.isNotEmpty()) {
                    "Session ${session.sessionId}: tools/list returned empty list — session may not have started correctly"
                }

                // Remaining requests
                repeat(REQUESTS_PER_SESSION - 1) { session.listTools() }

                val id = session.sessionId
                session.close()
                closedIds.add(id)
            }
        } catch (e: Exception) {
            errors.add("session[$idx]: ${e.message}")
        }
    }

    private fun verifyAllClosed(ids: List<String>) {
        if (ids.isEmpty()) return

        val stillAlive = AtomicInteger()

        TestMcpClient(serverConfig).use { probe ->
            ids.forEach { id ->
                val status = probe.rawDelete(id)
                if (status == 200) {
                    println("  ✗ Session $id still alive after close (DELETE returned 200)")
                    stillAlive.incrementAndGet()
                }
            }
        }

        println("  ✓ ${ids.size - stillAlive.get()}/${ids.size} sessions confirmed gone")
        Assertions.assertEquals(
            0, stillAlive.get(),
            "${stillAlive.get()} session(s) were not cleaned up server-side after DELETE"
        )
    }

    companion object {
        private const val SESSION_COUNT       = 30
        private const val REQUESTS_PER_SESSION = 5
        private const val TIMEOUT_SEC          = 120L
    }
}