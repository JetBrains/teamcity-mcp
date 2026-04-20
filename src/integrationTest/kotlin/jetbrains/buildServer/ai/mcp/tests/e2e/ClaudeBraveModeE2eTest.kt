package jetbrains.buildServer.ai.mcp.tests.e2e

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import jetbrains.buildServer.ai.mcp.framework.e2e.BraveModeControl
import jetbrains.buildServer.ai.mcp.framework.e2e.ScriptRunner
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * End-to-end tests that verify `teamcity.ai.mcp.braveMode.enabled` actually
 * gates destructive tool availability as advertised by the guides.
 *
 *  1. Safe mode — property disabled; the agent must not see `teamcity_rest_delete`
 *     and must not delete the scratch project.
 *  2. Brave mode — property enabled; the agent must see the tool, call it, and
 *     leave the scratch project deleted.
 *
 * Between the two tests the property is flipped by rewriting
 * `$TC_DATA_PATH/config/internal.properties`; the test polls the MCP tool list
 * until the server has picked up the change. A fresh Claude CLI invocation per
 * test re-opens the MCP session, so no explicit tool-reload call is needed.
 *
 * Prerequisites (in addition to other Claude e2e tests):
 *  - `TC_DATA_PATH` system property / env var pointing to the TeamCity data
 *    directory. Skipped if unset.
 *
 * These tests mutate server state, so the class is ordered to run last via
 * `@Order(Int.MAX_VALUE)` (see `junit-platform.properties`). Cleanup in
 * `@AfterAll` resets the property and removes the scratch project if present.
 */
@Tag("e2e")
@Order(Int.MAX_VALUE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ClaudeBraveModeE2eTest : McpIntegrationTestBase() {

    private val scripts = ScriptRunner(ScriptRunner.scriptsDir())
    private val scratchProjectId = "BraveE2eScratch"
    private var originalBraveMode: String? = null

    @BeforeAll
    fun setUp() {
        BraveModeControl.assumeAvailable()
        scripts.runChecked("common.sh", listOf("check-docker"))
        assertNotNull(findApiKey(), "ANTHROPIC_API_KEY not set")

        val dockerfileDir = System.getProperty("user.dir") + "/src/integrationTest/docker/claude-agent"
        println("  Building Docker image $IMAGE_TAG ...")
        scripts.runChecked("common.sh", listOf("build", dockerfileDir, IMAGE_TAG), timeoutSeconds = 600)
        scripts.runChecked("common.sh", listOf("start", CONTAINER_NAME, IMAGE_TAG))
        scripts.runChecked(
            "claude-agent.sh",
            listOf("configure", CONTAINER_NAME, serverConfig.mcpUrl, serverConfig.bearerToken)
        )

        originalBraveMode = BraveModeControl.readBraveMode()
        if (projectExists(scratchProjectId)) deleteProject(scratchProjectId)
    }

    @AfterAll
    fun tearDown() {
        runCatching { BraveModeControl.restoreBraveMode(originalBraveMode) }
        runCatching { if (projectExists(scratchProjectId)) deleteProject(scratchProjectId) }
        scripts.run("common.sh", listOf("stop", CONTAINER_NAME))
        println("  Container $CONTAINER_NAME removed, brave mode restored to $originalBraveMode")
    }

    @Test
    @Order(1)
    fun `safe mode hides teamcity_rest_delete - agent cannot delete project`() {
        BraveModeControl.setBraveMode(false)
        waitForDeleteToolVisible(false)
        createProject(scratchProjectId)

        val output = scripts.runWithRetry(
            "claude-agent.sh",
            listOf(
                "run-prompt", CONTAINER_NAME,
                "Permanently delete the TeamCity project with id '$scratchProjectId' " +
                    "using the available MCP tools. If you cannot do it with the tools you have, say why. " +
                    "Print exactly one final line: STATUS: <deleted|failed>.",
                findApiKey()!!
            ),
            timeoutSeconds = 180
        )

        output.dump("safe mode delete attempt")
            .assumeExternalApiAvailable("safe mode delete attempt")
            .assertExitCode(0, "safe mode delete attempt")

        val deleteCalled = output.toolCalls.any {
            it == "teamcity_rest_delete" || it.endsWith("__teamcity_rest_delete")
        }
        assertFalse(
            deleteCalled,
            "safe mode must not expose or allow calling teamcity_rest_delete. " +
                "Actual tool calls: ${output.toolCalls}"
        )
        assertTrue(
            projectExists(scratchProjectId),
            "scratch project '$scratchProjectId' must still exist in safe mode"
        )
    }

    @Test
    @Order(2)
    fun `brave mode exposes teamcity_rest_delete - agent deletes project`() {
        if (!projectExists(scratchProjectId)) createProject(scratchProjectId)
        BraveModeControl.setBraveMode(true)
        waitForDeleteToolVisible(true)

        val output = scripts.runWithRetry(
            "claude-agent.sh",
            listOf(
                "run-prompt", CONTAINER_NAME,
                "Use the teamcity_rest_delete MCP tool to delete the project at " +
                    "path '/app/rest/projects/id:$scratchProjectId'. " +
                    "Print exactly one final line: STATUS: <deleted|failed>.",
                findApiKey()!!
            ),
            timeoutSeconds = 180
        )

        output.dump("brave mode delete")
            .assumeExternalApiAvailable("brave mode delete")
            .assertExitCode(0, "brave mode delete")
            .assertToolCalled("teamcity_rest_delete")

        assertFalse(
            projectExists(scratchProjectId),
            "scratch project '$scratchProjectId' must be gone after brave-mode delete"
        )
    }

    /**
     * Poll the MCP tool list until `teamcity_rest_delete` visibility matches
     * [expectVisible]. Accounts for TC internal-properties refresh latency (~5–10s).
     */
    private fun waitForDeleteToolVisible(expectVisible: Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val visible = mcpClient().use { client ->
                var seen = false
                client.withSession {
                    seen = listTools().any { it.name == "teamcity_rest_delete" }
                }
                seen
            }
            if (visible == expectVisible) return
            Thread.sleep(1000)
        }
        throw AssertionError(
            "teamcity_rest_delete visibility did not reach $expectVisible within 30s. " +
                "Check that TC is reloading internal.properties from TC_DATA_PATH."
        )
    }

    companion object {
        private const val IMAGE_TAG = "tc-mcp-claude-agent:test"
        private const val CONTAINER_NAME = "tc-mcp-e2e-brave"

        private fun findApiKey(): String? =
            System.getProperty("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
                ?: System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
    }
}
