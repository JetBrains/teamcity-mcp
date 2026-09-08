package jetbrains.buildServer.ai.mcp.tests.brave

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import jetbrains.buildServer.ai.mcp.framework.BraveModeControl
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Verifies the `teamcity.ai.mcp.braveMode.enabled` boundary against a live server,
 * without an AI agent in the loop (the agent-driven variant is `ClaudeBraveModeE2eTest`).
 *
 * Three groups:
 *  1. [SafeMode]    — brave mode off: write tools and the brave guides are not exposed,
 *                     and the safe POST tool stays inside its build-queue allowlist.
 *  2. [BraveMode]   — brave mode on: the write tools appear and really mutate the server.
 *  3. [Permissions] — brave mode on, but the caller's TeamCity permissions still apply:
 *                     a permission-restricted token cannot write outside its scope.
 *
 * The mode is flipped by rewriting `$TC_DATA_PATH/config/internal.properties`
 * ([BraveModeControl]); `setBraveMode` polls the MCP tool list until the server has
 * picked the change up, and re-applying the current value is a no-op.
 *
 * Prerequisites:
 *  - `TC_DATA_PATH` pointing at the TeamCity data directory. Skipped if unset.
 *  - `TC_SERVER_RESTRICTED_TOKEN` for [Permissions] (produced by `scripts/setup-test-server.sh`).
 *
 * These tests change a server-wide property, so the class runs last
 * (`@Order(Int.MAX_VALUE)`, see `junit-platform.properties`) and `@AfterAll` restores
 * both the property and the scratch project.
 */
@Order(Int.MAX_VALUE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BraveModeIntegrationTest : McpIntegrationTestBase() {

    private val scratchProjectId = "BraveModeScratch"
    private var originalBraveMode: String? = null

    @BeforeAll
    fun setUp() {
        BraveModeControl.assumeAvailable()
        originalBraveMode = BraveModeControl.readBraveMode()
        if (projectExists(scratchProjectId)) deleteProject(scratchProjectId)
    }

    @AfterAll
    fun tearDown() {
        runCatching { if (projectExists(scratchProjectId)) deleteProject(scratchProjectId) }
        runCatching { BraveModeControl.restoreBraveMode(originalBraveMode) }
        println("  Brave mode restored to $originalBraveMode")
    }

    // ==================================================================
    // Safe mode — brave mode off
    // ==================================================================

    @Nested
    inner class SafeMode {

        @BeforeEach
        fun safeMode() = setBraveMode(false)

        @Test
        fun `write tools are not exposed`() {
            mcpClient().withSession {
                val toolNames = listTools().map { it.name }.toSet()

                assertTrue(REST_GET in toolNames, "Read tools must stay available, got $toolNames")
                assertTrue(REST_POST in toolNames, "The safe POST tool must stay available, got $toolNames")
                assertFalse(REST_PUT in toolNames, "$REST_PUT must be hidden in safe mode, got $toolNames")
                assertFalse(REST_DELETE in toolNames, "$REST_DELETE must be hidden in safe mode, got $toolNames")

                // Pipeline tools follow the same gate when teamcity.ai.mcp.pipeline.enabled is on.
                if (PIPELINE_GET in toolNames) {
                    assertFalse(PIPELINE_POST in toolNames, "$PIPELINE_POST must be hidden in safe mode, got $toolNames")
                    assertFalse(PIPELINE_DELETE in toolNames, "$PIPELINE_DELETE must be hidden in safe mode, got $toolNames")
                }
                println("  ✓ safe mode exposes ${toolNames.size} tool(s), none of them write tools")
            }
        }

        @Test
        fun `calling a hidden write tool fails and changes nothing`() {
            createProject(scratchProjectId)
            mcpClient().withSession {
                val result = callTool(REST_DELETE, mapOf("path" to "/app/rest/projects/id:$scratchProjectId"))

                assertTrue(result.isError, "$REST_DELETE must not be callable in safe mode, got: ${result.content}")
                assertTrue(
                    projectExists(scratchProjectId),
                    "Project '$scratchProjectId' must survive a safe-mode delete attempt"
                )
                println("  ✓ safe-mode $REST_DELETE rejected: ${result.content.first().text.take(120)}")
            }
            deleteProject(scratchProjectId)
        }

        @Test
        fun `rest post is limited to the build queue`() {
            mcpClient().withSession {
                val result = callTool(REST_POST, mapOf(
                    "path" to "/app/rest/projects",
                    "body" to """{"id":"$scratchProjectId","name":"$scratchProjectId","parentProject":{"id":"_Root"}}"""
                ))

                assertTrue(result.isError, "Safe-mode POST outside the allowlist must fail, got: ${result.content}")
                assertTrue(
                    result.content.first().text.contains("not allowed"),
                    "Expected an allowlist rejection, got: ${result.content.first().text}"
                )
                assertFalse(projectExists(scratchProjectId), "Safe-mode POST must not have created a project")
                println("  ✓ safe-mode $REST_POST is confined to /app/rest/buildQueue")
            }
        }

        @Test
        fun `rest api guide describes read-only tools only`() {
            mcpClient().withSession {
                val guide = readResource(REST_GUIDE_URI).first().text

                assertFalse(guide.contains(REST_DELETE), "The safe REST guide must not document $REST_DELETE")
                assertFalse(guide.contains(REST_PUT), "The safe REST guide must not document $REST_PUT")
                println("  ✓ safe mode serves the read-only REST API guide (${guide.length} chars)")
            }
        }
    }

    // ==================================================================
    // Brave mode — brave mode on
    // ==================================================================

    @Nested
    inner class BraveMode {

        @BeforeEach
        fun braveMode() = setBraveMode(true)

        @Test
        fun `write tools are exposed`() {
            mcpClient().withSession {
                val toolNames = listTools().map { it.name }.toSet()

                assertTrue(REST_PUT in toolNames, "$REST_PUT must be exposed in brave mode, got $toolNames")
                assertTrue(REST_DELETE in toolNames, "$REST_DELETE must be exposed in brave mode, got $toolNames")

                if (PIPELINE_GET in toolNames) {
                    assertTrue(PIPELINE_POST in toolNames, "$PIPELINE_POST must be exposed in brave mode, got $toolNames")
                    assertTrue(PIPELINE_DELETE in toolNames, "$PIPELINE_DELETE must be exposed in brave mode, got $toolNames")
                }
                println("  ✓ brave mode exposes the write tools")
            }
        }

        /**
         * The full write lifecycle through the MCP tools: arbitrary POST (no allowlist,
         * no injected `"personal": true`), PUT, then DELETE.
         */
        @Test
        fun `project can be created updated and deleted through the rest tools`() {
            mcpClient().withSession {
                val created = callTool(REST_POST, mapOf(
                    "path" to "/app/rest/projects",
                    "body" to """{"id":"$scratchProjectId","name":"$scratchProjectId","parentProject":{"id":"_Root"}}"""
                ))
                assertFalse(created.isError, "Brave-mode POST should create the project, got: ${created.content}")
                assertTrue(projectExists(scratchProjectId), "Project '$scratchProjectId' should exist after POST")

                val updated = callTool(REST_PUT, mapOf(
                    "path" to "/app/rest/projects/id:$scratchProjectId/description",
                    "body" to DESCRIPTION
                ))
                assertFalse(updated.isError, "Brave-mode PUT should update the description, got: ${updated.content}")

                val read = callTool(REST_GET, mapOf(
                    "path" to "/app/rest/projects/id:$scratchProjectId",
                    "query" to "fields=id,description"
                ))
                assertFalse(read.isError, "Reading the project back should succeed, got: ${read.content}")
                assertEquals(
                    DESCRIPTION,
                    extractBody(read)["description"]?.jsonPrimitive?.content,
                    "PUT should have persisted the new description"
                )

                val deleted = callTool(REST_DELETE, mapOf("path" to "/app/rest/projects/id:$scratchProjectId"))
                assertFalse(deleted.isError, "Brave-mode DELETE should remove the project, got: ${deleted.content}")
                assertFalse(projectExists(scratchProjectId), "Project '$scratchProjectId' should be gone after DELETE")

                println("  ✓ brave mode created, updated and deleted '$scratchProjectId' via MCP tools")
            }
        }

        @Test
        fun `rest api guide describes the write tools`() {
            mcpClient().withSession {
                val guide = readResource(REST_GUIDE_URI).first().text

                assertTrue(guide.contains(REST_DELETE), "The brave REST guide must document $REST_DELETE")
                assertTrue(guide.contains(REST_PUT), "The brave REST guide must document $REST_PUT")
                println("  ✓ brave mode serves the write-aware REST API guide (${guide.length} chars)")
            }
        }
    }

    // ==================================================================
    // Permissions — brave mode does not widen the caller's TeamCity permissions
    // ==================================================================

    /**
     * The restricted token is an admin token narrowed to `view_project` on
     * `McpPermissionVisible`, so in brave mode it still sees the write tools but every
     * write must be refused by TeamCity itself — inside its scope (403) and outside it
     * (403/404, the hidden project is not even visible).
     */
    @Nested
    inner class Permissions {

        @BeforeEach
        fun braveMode() = setBraveMode(true)

        @Test
        fun `restricted token sees the write tools`() {
            restrictedMcpClient().withSession {
                val toolNames = listTools().map { it.name }.toSet()

                assertTrue(REST_PUT in toolNames, "Tool exposure is server-wide, got $toolNames")
                assertTrue(REST_DELETE in toolNames, "Tool exposure is server-wide, got $toolNames")
                println("  ✓ restricted token sees the brave tools — the boundary is TC permissions, not tool visibility")
            }
        }

        @Test
        fun `restricted token cannot create a project`() {
            restrictedMcpClient().withSession {
                val result = callTool(REST_POST, mapOf(
                    "path" to "/app/rest/projects",
                    "body" to """{"id":"$scratchProjectId","name":"$scratchProjectId","parentProject":{"id":"_Root"}}"""
                ))

                assertDeniedByPermissions(result.isError, result.content.first().text, "POST /app/rest/projects")
                assertFalse(projectExists(scratchProjectId), "Restricted token must not have created a project")
            }
        }

        @Test
        fun `restricted token cannot modify the project it may only view`() {
            val before = projectDescription(VISIBLE_PROJECT_ID)

            restrictedMcpClient().withSession {
                val result = callTool(REST_PUT, mapOf(
                    "path" to "/app/rest/projects/id:$VISIBLE_PROJECT_ID/description",
                    "body" to "changed by a view-only token"
                ))

                assertDeniedByPermissions(result.isError, result.content.first().text, "PUT on $VISIBLE_PROJECT_ID")
            }
            assertEquals(before, projectDescription(VISIBLE_PROJECT_ID), "Description must be unchanged")
        }

        @Test
        fun `restricted token cannot delete a project outside its scope`() {
            restrictedMcpClient().withSession {
                val result = callTool(REST_DELETE, mapOf("path" to "/app/rest/projects/id:$HIDDEN_PROJECT_ID"))

                assertDeniedByPermissions(result.isError, result.content.first().text, "DELETE of $HIDDEN_PROJECT_ID")
            }
            assertTrue(projectExists(HIDDEN_PROJECT_ID), "Project '$HIDDEN_PROJECT_ID' must still exist")
        }

        /**
         * Asserts the call was refused by TeamCity's own access check — denied (403) or
         * hidden from the caller entirely (404) — rather than by some unrelated failure
         * such as brave mode being off, which would make these tests pass for the wrong reason.
         */
        private fun assertDeniedByPermissions(isError: Boolean, message: String, what: String) {
            assertTrue(isError, "$what must be refused for the restricted token, got: $message")
            assertTrue(
                ACCESS_DENIED_MARKERS.any { message.contains(it, ignoreCase = true) },
                "$what must fail on TeamCity permissions (one of $ACCESS_DENIED_MARKERS), got: $message"
            )
            println("  ✓ $what denied: ${message.lineSequence().first()}")
        }

        /** Reads a project description with the unrestricted token. */
        private fun projectDescription(projectId: String): String? {
            var description: String? = null
            mcpClient().withSession {
                val result = callTool(REST_GET, mapOf(
                    "path" to "/app/rest/projects/id:$projectId",
                    "query" to "fields=id,description"
                ))
                assertFalse(result.isError, "Reading '$projectId' should succeed, got: ${result.content}")
                description = extractBody(result)["description"]?.jsonPrimitive?.content
            }
            return description
        }
    }

    companion object {
        // Tool names are `internal` in the plugin module, so tests spell them out.
        private const val REST_GET = "teamcity_rest_get"
        private const val REST_POST = "teamcity_rest_post"
        private const val REST_PUT = "teamcity_rest_put"
        private const val REST_DELETE = "teamcity_rest_delete"
        private const val PIPELINE_GET = "teamcity_pipeline_get"
        private const val PIPELINE_POST = "teamcity_pipeline_post"
        private const val PIPELINE_DELETE = "teamcity_pipeline_delete"

        private const val REST_GUIDE_URI = "teamcity://guides/rest-api"
        private const val DESCRIPTION = "updated by BraveModeIntegrationTest"

        private const val VISIBLE_PROJECT_ID = "McpPermissionVisible"
        private const val HIDDEN_PROJECT_ID = "McpPermissionHidden"

        /** Signals that TeamCity itself refused the write, whatever shape the REST error takes. */
        private val ACCESS_DENIED_MARKERS = listOf("403", "404", "Access denied", "not found", "permission")
    }
}
