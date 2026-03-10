package jetbrains.buildServer.ai.mcp.tests.smoke

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import jetbrains.buildServer.ai.mcp.framework.TestMcpClient
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tests that list resources (testOccurrences, problemOccurrences, changes, builds)
 * respect pagination (count/start) when queried via top-level endpoints with locators.
 *
 * Validates that the recommended approach in the REST API guide works correctly:
 *   /app/rest/<items>?locator=<parent_filter>,count:N
 * instead of the sub-resource approach:
 *   /app/rest/<parent>/id:<id>/<items>  (which ignores pagination)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestListPaginationTest : McpIntegrationTestBase() {

    // ------------------------------------------------------------------
    // Builds — top-level with locator pagination
    // ------------------------------------------------------------------

    @Test
    fun `builds - count in locator limits returned items`() {
        mcpClient().withSession {
            val result = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/builds",
                "query" to "locator=defaultFilter:false,count:3&fields=count,build(id)"
            ))
            assertFalse(result.isError, "Request should succeed: ${result.content}")
            val body = extractBody(result)
            val count = body["count"]?.jsonPrimitive?.intOrNull ?: 0
            val builds = body["build"]?.jsonArray
            assertTrue(count <= 3, "count field should be <= 3, got $count")
            assertTrue((builds?.size ?: 0) <= 3, "Should return at most 3 builds, got ${builds?.size}")
            println("  ✓ builds with count:3 → returned $count item(s)")
        }
    }

    @Test
    fun `builds - start and count enable pagination`() {
        mcpClient().withSession {
            // First page
            val page1 = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/builds",
                "query" to "locator=defaultFilter:false,start:0,count:2&fields=count,build(id)"
            ))
            assertFalse(page1.isError, "Page 1 should succeed: ${page1.content}")
            val body1 = extractBody(page1)
            val ids1 = body1["build"]?.jsonArray?.map { it.jsonObject["id"]?.jsonPrimitive?.intOrNull } ?: emptyList()

            // Second page
            val page2 = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/builds",
                "query" to "locator=defaultFilter:false,start:2,count:2&fields=count,build(id)"
            ))
            assertFalse(page2.isError, "Page 2 should succeed: ${page2.content}")
            val body2 = extractBody(page2)
            val ids2 = body2["build"]?.jsonArray?.map { it.jsonObject["id"]?.jsonPrimitive?.intOrNull } ?: emptyList()

            // Pages should not overlap
            if (ids1.isNotEmpty() && ids2.isNotEmpty()) {
                val overlap = ids1.intersect(ids2.toSet())
                assertTrue(overlap.isEmpty(), "Pages should not overlap, but found: $overlap")
            }
            println("  ✓ builds pagination: page1=$ids1, page2=$ids2")
        }
    }

    // ------------------------------------------------------------------
    // Test occurrences — top-level with build locator
    // ------------------------------------------------------------------

    @Test
    fun `testOccurrences - top-level endpoint with build filter respects count`() {
        mcpClient().withSession {
            // First find a build that has tests
            val buildResult = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/builds",
                "query" to "locator=defaultFilter:false,count:20&fields=build(id,testOccurrences(count))"
            ))
            assertFalse(buildResult.isError, "Build query should succeed")
            val builds = extractBody(buildResult)["build"]?.jsonArray ?: JsonArray(emptyList())

            val buildWithTests = builds.firstOrNull { build ->
                val testCount = build.jsonObject["testOccurrences"]
                    ?.jsonObject?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                testCount > 3
            }

            if (buildWithTests == null) {
                println("  ⚠ No builds with >3 tests found, skipping test")
                return@withSession
            }

            val buildId = buildWithTests.jsonObject["id"]!!.jsonPrimitive.int
            val totalTests = buildWithTests.jsonObject["testOccurrences"]!!
                .jsonObject["count"]!!.jsonPrimitive.int

            // Query via top-level endpoint with count:3
            val result = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/testOccurrences",
                "query" to "locator=build:(id:$buildId),start:0,count:3&fields=count,testOccurrence(id,name)"
            ))
            assertFalse(result.isError, "testOccurrences query should succeed: ${result.content}")
            val body = extractBody(result)
            val returnedCount = body["count"]?.jsonPrimitive?.intOrNull ?: 0
            val items = body["testOccurrence"]?.jsonArray

            assertTrue(returnedCount <= 3,
                "Top-level testOccurrences with count:3 should return ≤3, got $returnedCount (build has $totalTests total)")
            assertTrue((items?.size ?: 0) <= 3,
                "Should return at most 3 items, got ${items?.size}")
            println("  ✓ testOccurrences top-level: build $buildId has $totalTests tests, count:3 → $returnedCount item(s)")
        }
    }

    @Test
    fun `testOccurrences - top-level pagination returns different pages`() {
        mcpClient().withSession {
            val buildResult = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/builds",
                "query" to "locator=defaultFilter:false,count:20&fields=build(id,testOccurrences(count))"
            ))
            assertFalse(buildResult.isError)
            val builds = extractBody(buildResult)["build"]?.jsonArray ?: JsonArray(emptyList())

            val buildWithTests = builds.firstOrNull { build ->
                val testCount = build.jsonObject["testOccurrences"]
                    ?.jsonObject?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                testCount > 4
            }

            if (buildWithTests == null) {
                println("  ⚠ No builds with >4 tests found, skipping test")
                return@withSession
            }

            val buildId = buildWithTests.jsonObject["id"]!!.jsonPrimitive.int

            val page1 = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/testOccurrences",
                "query" to "locator=build:(id:$buildId),start:0,count:2&fields=testOccurrence(id)"
            ))
            val page2 = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/testOccurrences",
                "query" to "locator=build:(id:$buildId),start:2,count:2&fields=testOccurrence(id)"
            ))
            assertFalse(page1.isError)
            assertFalse(page2.isError)

            val ids1 = extractBody(page1)["testOccurrence"]?.jsonArray
                ?.map { it.jsonObject["id"]?.jsonPrimitive?.content } ?: emptyList()
            val ids2 = extractBody(page2)["testOccurrence"]?.jsonArray
                ?.map { it.jsonObject["id"]?.jsonPrimitive?.content } ?: emptyList()

            if (ids1.isNotEmpty() && ids2.isNotEmpty()) {
                val overlap = ids1.intersect(ids2.toSet())
                assertTrue(overlap.isEmpty(), "Test occurrence pages should not overlap: $overlap")
            }
            println("  ✓ testOccurrences pagination: page1=${ids1.size} items, page2=${ids2.size} items")
        }
    }

    @Test
    fun `testOccurrences - status filter works in top-level locator`() {
        mcpClient().withSession {
            val buildResult = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/builds",
                "query" to "locator=status:FAILURE,defaultFilter:false,count:10&fields=build(id)"
            ))
            assertFalse(buildResult.isError)
            val builds = extractBody(buildResult)["build"]?.jsonArray ?: JsonArray(emptyList())
            val failedBuild = builds.firstOrNull()

            if (failedBuild == null) {
                println("  ⚠ No failed builds found, skipping test")
                return@withSession
            }

            val buildId = failedBuild.jsonObject["id"]!!.jsonPrimitive.int

            val result = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/testOccurrences",
                "query" to "locator=build:(id:$buildId),status:FAILURE,count:10&fields=testOccurrence(name,status)"
            ))
            assertFalse(result.isError, "Filtered testOccurrences should succeed: ${result.content}")
            val items = extractBody(result)["testOccurrence"]?.jsonArray ?: JsonArray(emptyList())

            items.forEach { item ->
                val status = item.jsonObject["status"]?.jsonPrimitive?.content
                assertEquals("FAILURE", status, "All filtered tests should have status FAILURE")
            }
            println("  ✓ testOccurrences status filter: ${items.size} failed test(s) in build $buildId")
        }
    }

    // ------------------------------------------------------------------
    // Problem occurrences — top-level with build locator
    // ------------------------------------------------------------------

    @Test
    fun `problemOccurrences - top-level endpoint with build filter works`() {
        mcpClient().withSession {
            // Find a failed build that should have problems
            val buildResult = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/builds",
                "query" to "locator=status:FAILURE,defaultFilter:false,count:5&fields=build(id,problemOccurrences(count))"
            ))
            assertFalse(buildResult.isError)
            val builds = extractBody(buildResult)["build"]?.jsonArray ?: JsonArray(emptyList())

            val buildWithProblems = builds.firstOrNull { build ->
                val problemCount = build.jsonObject["problemOccurrences"]
                    ?.jsonObject?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                problemCount > 0
            }

            if (buildWithProblems == null) {
                println("  ⚠ No builds with problems found, skipping test")
                return@withSession
            }

            val buildId = buildWithProblems.jsonObject["id"]!!.jsonPrimitive.int
            val expectedCount = buildWithProblems.jsonObject["problemOccurrences"]!!
                .jsonObject["count"]!!.jsonPrimitive.int

            val result = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/problemOccurrences",
                "query" to "locator=build:(id:$buildId),count:100&fields=count,problemOccurrence(id,type)"
            ))
            assertFalse(result.isError, "problemOccurrences query should succeed: ${result.content}")
            val body = extractBody(result)
            val returnedCount = body["count"]?.jsonPrimitive?.intOrNull ?: 0

            assertTrue(returnedCount > 0, "Should find problems for failed build $buildId")
            assertEquals(expectedCount, returnedCount,
                "Top-level count should match embedded count for build $buildId")
            println("  ✓ problemOccurrences top-level: build $buildId → $returnedCount problem(s)")
        }
    }

    @Test
    fun `problemOccurrences - count in locator limits results`() {
        mcpClient().withSession {
            val buildResult = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/builds",
                "query" to "locator=status:FAILURE,defaultFilter:false,count:10&fields=build(id,problemOccurrences(count))"
            ))
            assertFalse(buildResult.isError)
            val builds = extractBody(buildResult)["build"]?.jsonArray ?: JsonArray(emptyList())

            val buildWithManyProblems = builds.firstOrNull { build ->
                val problemCount = build.jsonObject["problemOccurrences"]
                    ?.jsonObject?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                problemCount > 1
            }

            if (buildWithManyProblems == null) {
                println("  ⚠ No builds with >1 problems found, skipping test")
                return@withSession
            }

            val buildId = buildWithManyProblems.jsonObject["id"]!!.jsonPrimitive.int

            val result = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/problemOccurrences",
                "query" to "locator=build:(id:$buildId),start:0,count:1&fields=count,problemOccurrence(id)"
            ))
            assertFalse(result.isError)
            val returnedCount = extractBody(result)["count"]?.jsonPrimitive?.intOrNull ?: 0
            assertTrue(returnedCount <= 1,
                "problemOccurrences with count:1 should return ≤1, got $returnedCount")
            println("  ✓ problemOccurrences count limit: build $buildId, count:1 → $returnedCount item(s)")
        }
    }

    // ------------------------------------------------------------------
    // Changes — top-level with build locator
    // ------------------------------------------------------------------

    @Test
    fun `changes - top-level endpoint with build filter works`() {
        mcpClient().withSession {
            // Find a build with changes
            val buildResult = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/builds",
                "query" to "locator=defaultFilter:false,count:20&fields=build(id,changes(count))"
            ))
            assertFalse(buildResult.isError)
            val builds = extractBody(buildResult)["build"]?.jsonArray ?: JsonArray(emptyList())

            val buildWithChanges = builds.firstOrNull { build ->
                val changeCount = build.jsonObject["changes"]
                    ?.jsonObject?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                changeCount > 0
            }

            if (buildWithChanges == null) {
                println("  ⚠ No builds with changes found, skipping test")
                return@withSession
            }

            val buildId = buildWithChanges.jsonObject["id"]!!.jsonPrimitive.int
            val expectedCount = buildWithChanges.jsonObject["changes"]!!
                .jsonObject["count"]!!.jsonPrimitive.int

            val result = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/changes",
                "query" to "locator=build:(id:$buildId),count:100&fields=count,change(id,username)"
            ))
            assertFalse(result.isError, "changes query should succeed: ${result.content}")
            val returnedCount = extractBody(result)["count"]?.jsonPrimitive?.intOrNull ?: 0

            assertTrue(returnedCount > 0, "Should find changes for build $buildId")
            println("  ✓ changes top-level: build $buildId → $returnedCount change(s) (expected $expectedCount)")
        }
    }

    @Test
    fun `changes - count in locator limits results`() {
        mcpClient().withSession {
            val buildResult = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/builds",
                "query" to "locator=defaultFilter:false,count:20&fields=build(id,changes(count))"
            ))
            assertFalse(buildResult.isError)
            val builds = extractBody(buildResult)["build"]?.jsonArray ?: JsonArray(emptyList())

            val buildWithChanges = builds.firstOrNull { build ->
                val changeCount = build.jsonObject["changes"]
                    ?.jsonObject?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                changeCount > 2
            }

            if (buildWithChanges == null) {
                println("  ⚠ No builds with >2 changes found, skipping test")
                return@withSession
            }

            val buildId = buildWithChanges.jsonObject["id"]!!.jsonPrimitive.int

            val result = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/changes",
                "query" to "locator=build:(id:$buildId),start:0,count:2&fields=count,change(id)"
            ))
            assertFalse(result.isError)
            val returnedCount = extractBody(result)["count"]?.jsonPrimitive?.intOrNull ?: 0
            assertTrue(returnedCount <= 2,
                "changes with count:2 should return ≤2, got $returnedCount")
            println("  ✓ changes count limit: build $buildId, count:2 → $returnedCount item(s)")
        }
    }

    // ------------------------------------------------------------------
    // Agents — top-level with locator pagination
    // ------------------------------------------------------------------

    @Test
    fun `agents - count in locator limits results`() {
        mcpClient().withSession {
            val result = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/agents",
                "query" to "locator=authorized:any,start:0,count:1&fields=count,agent(id,name)"
            ))
            assertFalse(result.isError, "agents query should succeed: ${result.content}")
            val body = extractBody(result)
            val agents = body["agent"]?.jsonArray
            assertTrue((agents?.size ?: 0) <= 1, "Should return at most 1 agent, got ${agents?.size}")
            println("  ✓ agents count limit: count:1 → ${agents?.size ?: 0} item(s)")
        }
    }

    // ------------------------------------------------------------------
    // Projects — top-level with locator pagination
    // ------------------------------------------------------------------

    @Test
    fun `projects - count in locator limits results`() {
        mcpClient().withSession {
            val result = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/projects",
                "query" to "locator=start:0,count:2&fields=count,project(id,name)"
            ))
            assertFalse(result.isError, "projects query should succeed: ${result.content}")
            val body = extractBody(result)
            val projects = body["project"]?.jsonArray
            assertTrue((projects?.size ?: 0) <= 2, "Should return at most 2 projects, got ${projects?.size}")
            println("  ✓ projects count limit: count:2 → ${projects?.size ?: 0} item(s)")
        }
    }

    // ------------------------------------------------------------------
    // Build types — top-level with locator pagination
    // ------------------------------------------------------------------

    @Test
    fun `buildTypes - count in locator limits results`() {
        mcpClient().withSession {
            val result = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/buildTypes",
                "query" to "locator=start:0,count:2&fields=count,buildType(id,name)"
            ))
            assertFalse(result.isError, "buildTypes query should succeed: ${result.content}")
            val body = extractBody(result)
            val buildTypes = body["buildType"]?.jsonArray
            assertTrue((buildTypes?.size ?: 0) <= 2, "Should return at most 2 buildTypes, got ${buildTypes?.size}")
            println("  ✓ buildTypes count limit: count:2 → ${buildTypes?.size ?: 0} item(s)")
        }
    }

    // ------------------------------------------------------------------
    // Investigations — top-level with locator
    // ------------------------------------------------------------------

    @Test
    fun `investigations - top-level endpoint with locator works`() {
        mcpClient().withSession {
            val result = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/investigations",
                "query" to "locator=count:5&fields=count,investigation(id,state)"
            ))
            assertFalse(result.isError, "investigations query should succeed: ${result.content}")
            val body = extractBody(result)
            val count = body["count"]?.jsonPrimitive?.intOrNull ?: 0
            assertTrue(count <= 5, "Should return at most 5 investigations, got $count")
            println("  ✓ investigations: count:5 → $count item(s)")
        }
    }

    // ------------------------------------------------------------------
    // Helper: extract body from tool result
    // ------------------------------------------------------------------

    private fun extractBody(result: TestMcpClient.ToolResult): JsonObject {
        val text = result.content.first().text
        val envelope = Json.parseToJsonElement(text).jsonObject
        return envelope["body"]?.jsonObject ?: JsonObject(emptyMap())
    }
}
