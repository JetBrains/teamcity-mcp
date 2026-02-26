package jetbrains.buildServer.ai.mcp.tests.e2e

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import jetbrains.buildServer.ai.mcp.framework.e2e.ScriptRunner
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertNotNull

/**
 * End-to-end tests that run Claude Code inside a Docker container against
 * the TeamCity MCP server.
 *
 * Approach is following:
 *  - A Docker image with Claude Code CLI is built once per test class
 *  - A container runs with `sleep infinity`; prompts are executed via `docker exec`
 *  - ANTHROPIC_API_KEY is injected via `docker exec -e` at runtime, never stored on disk.
 *    The bearer token is written to `.mcp.json` in the container for MCP auth headers.
 *    The container is ephemeral and force-removed in tearDown.
 *  - NDJSON output (`--output-format stream-json`) is parsed for assertions
 *
 * Prerequisites:
 *  - Docker daemon running
 *  - ANTHROPIC_API_KEY environment variable or system property set
 *  - TeamCity server running with MCP plugin deployed
 *
 * Run with:
 * ```
 * ./gradlew e2eTest -DANTHROPIC_API_KEY=sk-...
 * ```
 *
 * Skipped automatically when Docker is unavailable or API key is missing.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClaudeAgentE2eTest : McpIntegrationTestBase() {

    private val scripts = ScriptRunner(ScriptRunner.scriptsDir())

    @BeforeAll
    fun setUp() {
        scripts.runChecked("common.sh", listOf("check-docker"))
        assertNotNull(findApiKey(), "ANTHROPIC_API_KEY not set")

        val dockerfileDir = System.getProperty("user.dir") + "/src/integrationTest/docker/claude-agent"
        println("  Building Docker image $IMAGE_TAG ...")
        scripts.runChecked("common.sh", listOf("build", dockerfileDir, IMAGE_TAG), timeoutSeconds = 600)

        scripts.runChecked("common.sh", listOf("start", CONTAINER_NAME, IMAGE_TAG))
        scripts.runChecked("claude-agent.sh", listOf("configure", CONTAINER_NAME, serverConfig.mcpUrl, serverConfig.bearerToken))
        println("  Container $CONTAINER_NAME started, MCP configured for ${serverConfig.mcpUrl}")
    }

    @AfterAll
    fun tearDown() {
        scripts.run("common.sh", listOf("stop", CONTAINER_NAME))
        println("  Container $CONTAINER_NAME removed")
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `agent discovers TeamCity MCP tools`() {
        val output = scripts.run("claude-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "List all available MCP tools. For each tool print exactly one line:\n" +
                    "TOOL: <name> - <description>\n" +
                    "Do not call any tool. Only list them.",
                findApiKey()!!))

        output.dump("tool discovery")
            .assertNoErrors()
            .assertExitCode(0, "tool discovery")
            .assertOutputContains("introduce_yourself", "tool listing must include introduce_yourself")
    }

    @Test
    fun `agent calls introduce_yourself tool and gets a response`() {
        val output = scripts.run("claude-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "Call the MCP tool named \"introduce_yourself\" exactly once with name \"Claude\".\n" +
                    "Print the tool's text result on a line starting with:\n" +
                    "RESULT: <the text returned by the tool>",
                findApiKey()!!))

        output.dump("tool call")
            .assertNoErrors()
            .assertExitCode(0, "tool call")
            .assertToolCalled("introduce_yourself")
            .assertOutputContains("RESULT:")
    }

    @Test
    fun `agent can list tools and call one in a single turn`() {
        val output = scripts.run("claude-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "1. List all available MCP tools. Print each as: TOOL: <name>\n" +
                    "2. Then call \"introduce_yourself\" tool.\n" +
                    "3. Print the result as: RESULT: <text>",
                findApiKey()!!))

        output.dump("combined prompt")
            .assertNoErrors()
            .assertExitCode(0, "combined prompt")
            .assertOutputContains("TOOL:")
            .assertOutputContains("RESULT:")
            .assertToolCalled("introduce_yourself")
    }

    @Test
    fun `agent discovers TeamCity MCP resources`() {
        val output = scripts.run("claude-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "You have access to MCP resources (read-only data) in addition to tools. " +
                    "List all MCP resources available to you. " +
                    "For each resource include its URI and name in your response.",
                findApiKey()!!))

        output.dump("resource discovery")
            .assertExitCode(0, "resource discovery")
            .assertOutputContainsAny(
                "teamcity://", "introduce-yourself", "introduce_yourself",
                "rest-api", "rest_api_guide",
                message = "output must mention at least one resource URI or name"
            )
    }

    @Test
    fun `agent reads introduce_yourself resource`() {
        val output = scripts.run("claude-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "You have access to MCP resources. " +
                    "Read the MCP resource with URI \"teamcity://info/introduce-yourself\" " +
                    "and include its full content in your response.",
                findApiKey()!!))

        output.dump("resource read")
            .assertExitCode(0, "resource read")
            .assertOutputContainsAny(
                "TeamCity MCP server", "tools and resources", "AI agents",
                message = "output must contain content from the introduce_yourself resource"
            )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    companion object {
        private const val IMAGE_TAG = "tc-mcp-claude-agent:test"
        private const val CONTAINER_NAME = "tc-mcp-e2e-claude"

        private fun findApiKey(): String? =
            System.getProperty("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
                ?: System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
    }
}
