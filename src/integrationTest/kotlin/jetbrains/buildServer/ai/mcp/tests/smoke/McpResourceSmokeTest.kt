package jetbrains.buildServer.ai.mcp.tests.smoke

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Smoke tests for MCP resources — verifies resources/list and resources/read
 * work end-to-end, independently of specific resource content.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpResourceSmokeTest : McpIntegrationTestBase() {

    // -----------------------------------------------------------------------
    // Resources listing
    // -----------------------------------------------------------------------

    @Test
    fun `resources list is non-empty`() {
        mcpClient().use { client ->
            client.withSession {
                val resources = listResources()
                assertFalse(resources.isEmpty(), "Server must expose at least one resource")
                println("  ✓ Resource count: ${resources.size}")
            }
        }
    }

    @Test
    fun `resources list is idempotent`() {
        mcpClient().use { client ->
            client.withSession {
                val first = listResources().map { it.uri }.sorted()
                val second = listResources().map { it.uri }.sorted()
                assertEquals(first, second, "resources/list must return the same result on every call")
                println("  ✓ Consistent resource list: $first")
            }
        }
    }

    @Test
    fun `each resource has uri, name, description, and mimeType`() {
        mcpClient().use { client ->
            client.withSession {
                val resources = listResources()
                resources.forEach { resource ->
                    assertTrue(resource.uri.isNotBlank(), "Resource URI must be non-blank")
                    assertTrue(resource.name.isNotBlank(), "Resource '${resource.uri}' name must be non-blank")
                    assertTrue(resource.description.isNotBlank(), "Resource '${resource.uri}' description must be non-blank")
                    assertTrue(resource.mimeType.isNotBlank(), "Resource '${resource.uri}' mimeType must be non-blank")
                }
                println("  ✓ All ${resources.size} resources have uri, name, description, and mimeType")
            }
        }
    }

    @Test
    fun `concurrent resources-list calls in same session all succeed`() {
        mcpClient().use { client ->
            client.withSession {
                val futures = (1..5).map {
                    CompletableFuture.supplyAsync { listResources() }
                }
                val results = futures.map { it.get(30, TimeUnit.SECONDS) }
                assertTrue(
                    results.all { it.isNotEmpty() },
                    "All concurrent resources/list calls must return non-empty results"
                )
                println("  ✓ ${results.size} concurrent calls, each returned ${results.first().size} resource(s)")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Resource-specific: introduce_yourself
    // -----------------------------------------------------------------------

    @Test
    fun `resources list contains introduce_yourself resource`() {
        mcpClient().use { client ->
            client.withSession {
                val resources = listResources()
                val uris = resources.map { it.uri }
                assertTrue(
                    "teamcity://info/introduce-yourself" in uris,
                    "resources/list must include introduce_yourself resource, got: $uris"
                )
                println("  ✓ introduce_yourself resource found")
            }
        }
    }

    @Test
    fun `introduce_yourself resource can be read and returns content`() {
        mcpClient().use { client ->
            client.withSession {
                val contents = readResource("teamcity://info/introduce-yourself")
                assertFalse(contents.isEmpty(), "Resource read must return content")
                val text = contents.first().text
                assertTrue(text.isNotBlank(), "Resource content must be non-blank")
                assertTrue(
                    text.contains("TeamCity MCP server"),
                    "introduce_yourself content should mention TeamCity MCP server, got: $text"
                )
                println("  ✓ introduce_yourself content: $text")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Resource-specific: REST API guide
    // -----------------------------------------------------------------------

    @Test
    fun `resources list contains REST API guide resource`() {
        mcpClient().use { client ->
            client.withSession {
                val resources = listResources()
                val uris = resources.map { it.uri }
                assumeTrue(
                    "teamcity://guides/rest-api" in uris,
                    "REST API guide resource not enabled on this server (enabled: $uris) — skipping"
                )
                println("  ✓ REST API guide resource found")
            }
        }
    }

    @Test
    fun `REST API guide resource can be read and returns markdown`() {
        mcpClient().use { client ->
            client.withSession {
                val uris = listResources().map { it.uri }
                assumeTrue(
                    "teamcity://guides/rest-api" in uris,
                    "REST API guide resource not enabled on this server — skipping"
                )

                val contents = readResource("teamcity://guides/rest-api")
                assertFalse(contents.isEmpty(), "Resource read must return content")
                val content = contents.first()
                assertEquals("text/markdown", content.mimeType, "REST API guide should have text/markdown mimeType")
                assertTrue(content.text.contains("teamcity_rest_get"), "Guide should mention teamcity_rest_get tool")
                assertTrue(content.text.contains("/app/rest/"), "Guide should contain REST API paths")
                println("  ✓ REST API guide content length: ${content.text.length} chars")
            }
        }
    }

    @Test
    fun `resources list contains pipelines guide resource`() {
        mcpClient().use { client ->
            client.withSession {
                val resources = listResources()
                val uris = resources.map { it.uri }
                assumeTrue(
                    "teamcity://guides/pipelines" in uris,
                    "Pipelines guide resource not enabled on this server (enabled: $uris) — skipping"
                )
                println("  ✓ Pipelines guide resource found")
            }
        }
    }

    @Test
    fun `pipelines guide resource can be read and returns markdown`() {
        mcpClient().use { client ->
            client.withSession {
                val uris = listResources().map { it.uri }
                assumeTrue(
                    "teamcity://guides/pipelines" in uris,
                    "Pipelines guide resource not enabled on this server — skipping"
                )

                val contents = readResource("teamcity://guides/pipelines")
                assertFalse(contents.isEmpty(), "Resource read must return content")
                val content = contents.first()
                assertEquals("text/markdown", content.mimeType, "Pipelines guide should have text/markdown mimeType")
                assertTrue(content.text.contains("teamcity_pipeline_get"), "Guide should mention dedicated pipeline GET tool")
                assertTrue(content.text.contains("/app/pipeline"), "Guide should describe /app/pipeline endpoints")
                assertTrue(content.text.contains("/app/rest/pipelines"), "Guide should describe pipeline REST endpoints")
                println("  ✓ Pipelines guide content length: ${content.text.length} chars")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Reading non-existent resource
    // -----------------------------------------------------------------------

    @Test
    fun `reading unknown resource returns error`() {
        mcpClient().use { client ->
            client.withSession {
                try {
                    readResource("teamcity://nonexistent/resource")
                    fail("Reading non-existent resource should throw")
                } catch (e: Exception) {
                    println("  ✓ Unknown resource → error: ${e.message}")
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Multi-operation session with resources
    // -----------------------------------------------------------------------

    @Test
    fun `tools and resources can be listed in the same session`() {
        mcpClient().use { client ->
            client.withSession {
                val tools = listTools()
                val resources = listResources()
                assertFalse(tools.isEmpty(), "tools/list must return at least one tool")
                assertFalse(resources.isEmpty(), "resources/list must return at least one resource")
                println("  ✓ Same session: ${tools.size} tools, ${resources.size} resources")
            }
        }
    }
}
