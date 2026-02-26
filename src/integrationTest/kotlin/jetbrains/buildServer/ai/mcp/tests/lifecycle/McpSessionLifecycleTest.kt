package jetbrains.buildServer.ai.mcp.tests.lifecycle

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests verifying that MCP sessions and their server-side resources are correctly
 * cleaned up under every termination path.
 *
 * Each test confirms cleanup by probing the session with a POST after the expected
 * cleanup event: a properly cleaned-up session must return HTTP 404.
 *
 * Termination paths covered:
 *  1. Client DELETE before any requests
 *  2. Client DELETE after normal use
 *  3. Session initialized with rawInit, cleaned up via DELETE
 *  4. Session initialized with rawInit, persists until explicitly deleted
 *  5. Concurrent DELETEs racing on the same session
 *  6. High-volume rapid init-and-delete cycles (leak regression)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpSessionLifecycleTest : McpIntegrationTestBase() {

    // A minimal JSON-RPC body used solely to probe whether a session exists.
    // The server returns 404 for unknown sessions before parsing the body.
    private val probe = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""

    // -------------------------------------------------------------------------
    // Path 1 & 2: normal DELETE paths
    // -------------------------------------------------------------------------

    @Test
    fun `session deleted before any requests is immediately gone`() {
        mcpClient().use { client ->
            val session = client.initialize()
            val id = session.sessionId
            session.close() // DELETE, no prior requests
            Thread.sleep(200)
            Assertions.assertEquals(
                404, client.rawPost(id, probe),
                "Session must be gone immediately after DELETE (no prior requests)"
            )
        }
    }

    @Test
    fun `session deleted after normal use is immediately gone`() {
        mcpClient().use { client ->
            val session = client.initialize()
            session.listTools()
            val id = session.sessionId
            session.close()
            Thread.sleep(200)
            Assertions.assertEquals(
                404, client.rawPost(id, probe),
                "Session must be gone immediately after DELETE (after normal use)"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Paths 3 & 4: session created with rawInit (no subsequent requests)
    // -------------------------------------------------------------------------

    @Test
    fun `session created with rawInit is cleaned up by explicit DELETE`() {
        mcpClient().use { client ->
            val id = client.rawInit()
            val deleteStatus = client.rawDelete(id)
            Assertions.assertEquals(200, deleteStatus, "DELETE of a session must return 200")
            Thread.sleep(200)
            Assertions.assertEquals(
                404, client.rawPost(id, probe),
                "Session must be gone after DELETE"
            )
        }
    }

    @Test
    fun `session created with rawInit persists until explicitly deleted`() {
        mcpClient().use { client ->
            val id = client.rawInit()

            // Session should still be alive — POST should be accepted
            val statusAfterPost = client.rawPost(id, probe)
            Assertions.assertTrue(
                statusAfterPost in 200..202,
                "POST to session must be accepted (got $statusAfterPost)"
            )

            // Session must still be alive
            val stillAlive = client.rawPost(id, probe)
            Assertions.assertTrue(
                stillAlive != 404,
                "Session must persist after POST"
            )

            // Explicit DELETE must clean it up
            Assertions.assertEquals(200, client.rawDelete(id))
            Thread.sleep(200)
            Assertions.assertEquals(
                404, client.rawPost(id, probe),
                "Session must be gone after explicit DELETE"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Path 5: concurrent DELETEs
    // -------------------------------------------------------------------------

    @Test
    fun `concurrent DELETEs on the same session are handled safely`() {
        mcpClient().use { client ->
            val session = client.initialize()
            val id = session.sessionId

            val executor = Executors.newFixedThreadPool(5)
            val statuses = (1..5).map {
                CompletableFuture.supplyAsync({ client.rawDelete(id) }, executor)
            }.map { it.get(15, TimeUnit.SECONDS) }
            executor.shutdown()

            println("  Concurrent DELETE statuses: $statuses")
            Assertions.assertTrue(
                statuses.none { it >= 500 },
                "Concurrent DELETEs must not cause server errors, got: $statuses"
            )
            Assertions.assertEquals(
                404, client.rawPost(id, probe),
                "Session must be gone after concurrent DELETEs"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Path 6: rapid create-and-delete cycles (leak regression)
    // -------------------------------------------------------------------------

    @Test
    fun `rapid create-and-delete cycles leave no leaked sessions`() {
        val count = 50
        val executor = Executors.newCachedThreadPool()

        val ids = (1..count).map {
            CompletableFuture.supplyAsync({
                mcpClient().use { client ->
                    val id = client.rawInit()
                    client.rawDelete(id)
                    id
                }
            }, executor)
        }.map { it.get(60, TimeUnit.SECONDS) }

        executor.shutdown()

        mcpClient().use { verifier ->
            val leaked = ids.filter { verifier.rawPost(it, probe) != 404 }
            Assertions.assertTrue(
                leaked.isEmpty(),
                "${leaked.size}/$count sessions were not cleaned up after DELETE: $leaked"
            )
        }
        println("  ✓ $count rapid create-delete cycles, no leaks")
    }

    // -------------------------------------------------------------------------

}