package jetbrains.buildServer.ai.mcp.tests.e2e

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import jetbrains.buildServer.ai.mcp.framework.e2e.AgentOutput
import jetbrains.buildServer.ai.mcp.framework.e2e.FusLogAssertions
import jetbrains.buildServer.ai.mcp.framework.e2e.ScriptRunner
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * End-to-end tests that run Google Gemini CLI inside a Docker container against
 * the TeamCity MCP server.
 *
 * Following the approach from https://jonnyzzz.com/blog/2026/02/21/testing-mcp-server-with-ai-agents/:
 *  - A Docker image with Gemini CLI is built once per test class
 *  - A container runs with `sleep infinity`; prompts are executed via `docker exec`
 *  - GEMINI_API_KEY is injected via `docker exec -e` at runtime. The bearer token is
 *    written to `settings.json` in the container for MCP auth headers. The container
 *    is ephemeral and force-removed in tearDown.
 *  - NDJSON output (`--output-format stream-json`) is parsed for assertions
 *
 * Gemini quirks handled:
 *  - `--sandbox-mode none` vs `--sandbox false` flag compatibility (retry on unknown arg)
 *  - Gemini CLI may not exit after `--prompt` (MCP connection keeps it alive);
 *    `timeout 120` inside docker exec kills it, producing exit 124 or 137
 *  - Exit codes 124 (timeout SIGTERM) and 137 (SIGKILL) treated as success
 *    when NDJSON contains `"status":"success"`
 *
 * Prerequisites:
 *  - Docker daemon running
 *  - GEMINI_API_KEY environment variable or system property set
 *  - TeamCity server running with MCP plugin deployed
 *
 * Run with:
 * ```
 * ./gradlew e2eTest -DGEMINI_API_KEY=...
 * ```
 *
 * Skipped automatically when Docker is unavailable or API key is missing.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeminiAgentE2eTest : McpIntegrationTestBase() {

    private val scripts = ScriptRunner(ScriptRunner.scriptsDir())

    /** Whether the agent exposes MCP resource tools (detected during setUp). */
    private var supportsResources = false

    @BeforeAll
    fun setUp() {
        scripts.runChecked("common.sh", listOf("check-docker"))
        assertNotNull(findApiKey(), "GEMINI_API_KEY not set")

        val dockerfileDir = System.getProperty("user.dir") + "/src/integrationTest/docker/gemini-agent"
        println("  Building Docker image $IMAGE_TAG ...")
        scripts.runChecked("common.sh", listOf("build", dockerfileDir, IMAGE_TAG), timeoutSeconds = 600)

        scripts.runChecked("common.sh", listOf("start", CONTAINER_NAME, IMAGE_TAG))
        scripts.runChecked("gemini-agent.sh", listOf("configure", CONTAINER_NAME, serverConfig.mcpUrl, serverConfig.bearerToken, findApiKey()!!))
        println("  Container $CONTAINER_NAME started, MCP configured for ${serverConfig.mcpUrl}")

        // Quick probe to check if the agent exposes MCP resource tools.
        // Gemini CLI (as of v0.29.5) does not support resources in non-interactive mode.
        // Check both the init event tools array (Claude) and raw NDJSON for known tool names.
        val probe = scripts.run("gemini-agent.sh",
            listOf("run-prompt", CONTAINER_NAME, "Say hello.", findApiKey()!!))
        val probeLower = probe.stdout.lowercase()
        supportsResources = probe.availableTools.any { "resource" in it.lowercase() } ||
            "listmcpresourcestool" in probeLower || "readmcpresourcetool" in probeLower ||
            "list_mcp_resources" in probeLower || "read_mcp_resource" in probeLower
        println("  Agent supports MCP resources: $supportsResources")
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
        val output = scripts.run("gemini-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "List all available MCP tools. For each tool print exactly one line:\n" +
                    "TOOL: <name> - <description>\n" +
                    "Do not call any tool. Only list them.",
                findApiKey()!!))

        output.dump("tool discovery")
            .assertGeminiSuccess("tool discovery")
            // Gemini's delta streaming may split "introduce_yourself" across message boundaries,
            // so check for partial matches that survive the split.
            .assertOutputContainsAny(
                "introduce_yourself", "introduce_", "_yourself",
                message = "tool listing must include introduce_yourself"
            )
    }

    @Test
    fun `agent calls introduce_yourself tool and gets a response`() {
        val output = scripts.run("gemini-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "Call the MCP tool named \"introduce_yourself\" exactly once with name \"Gemini\".\n" +
                    "Print the tool's text result on a line starting with:\n" +
                    "RESULT: <the text returned by the tool>",
                findApiKey()!!))

        output.dump("tool call")
            .assertGeminiSuccess("tool call")
            .assertToolCalled("introduce_yourself")
            .assertOutputContains("RESULT:")

        FusLogAssertions.assertFusEventsLogged(
            "ai.mcp.session.requested",
            "ai.mcp.session.started",
            "ai.mcp.session.message.received",
            message = "Gemini agent FUS events"
        )
    }

    @Test
    fun `agent can list tools and call one in a single turn`() {
        val output = scripts.run("gemini-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "1. List all available MCP tools. Print each as: TOOL: <name>\n" +
                    "2. Then call \"introduce_yourself\" tool.\n" +
                    "3. Print the result as: RESULT: <text>",
                findApiKey()!!))

        output.dump("combined prompt")
            .assertGeminiSuccess("combined prompt")
            .assertOutputContains("TOOL:")
            .assertOutputContains("RESULT:")
            .assertToolCalled("introduce_yourself")
    }

    @Test
    fun `agent retrieves server info via REST tool`() {
        val output = scripts.run("gemini-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "Use the teamcity_rest_get tool to call /app/rest/server with query fields=version,buildNumber.\n" +
                    "Print the result on a line starting with:\n" +
                    "SERVER: version=<version> build=<buildNumber>",
                findApiKey()!!))

        output.dump("rest tool")
            .assertGeminiSuccess("rest tool")
            .assertToolCalled("teamcity_rest_get")
            .assertOutputContainsAny(
                "SERVER:", "version",
                message = "agent must report server info retrieved via REST tool"
            )
    }

    @Test
    fun `agent discovers TeamCity MCP resources`() {
        assumeTrue(supportsResources,
            "Gemini CLI does not expose MCP resource tools in non-interactive mode — skipping")

        val output = scripts.run("gemini-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "You have access to MCP resources (read-only data) in addition to tools. " +
                    "List all MCP resources available to you. " +
                    "For each resource include its URI and name in your response.",
                findApiKey()!!))

        output.dump("resource discovery")
            .assertGeminiSuccess("resource discovery")
            .assertOutputContainsAny(
                "teamcity://", "introduce-yourself", "introduce_yourself",
                "rest-api", "rest_api_guide",
                message = "output must mention at least one resource URI or name"
            )
    }

    @Test
    fun `agent reads introduce_yourself resource`() {
        assumeTrue(supportsResources,
            "Gemini CLI does not expose MCP resource tools in non-interactive mode — skipping")

        val output = scripts.run("gemini-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "You have access to MCP resources. " +
                    "Read the MCP resource with URI \"teamcity://info/introduce-yourself\" " +
                    "and include its full content in your response.",
                findApiKey()!!))

        output.dump("resource read")
            .assertGeminiSuccess("resource read")
            .assertOutputContainsAny(
                "TeamCity MCP server", "tools and resources", "AI agents",
                message = "output must contain content from the introduce_yourself resource"
            )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Gemini may not exit cleanly after processing a prompt — the MCP connection
     * keeps the process alive.  The shell script wraps it with `timeout 120`, so
     * exit 124 (SIGTERM from timeout) or 137 (SIGKILL) are expected when the
     * NDJSON stream already contains a `"status":"success"` result event.
     */
    private fun AgentOutput.assertGeminiSuccess(label: String): AgentOutput {
        if (exitCode == 0) return this
        if (exitCode in listOf(124, 137) && stdout.contains("\"status\":\"success\"")) {
            // Gemini doesn't exit after prompt — killed by timeout (124) or SIGKILL (137)
            return this
        }
        Assertions.fail<Unit>(
            "$label exited with $exitCode (expected 0, or 124/137 with success output).\nstderr:\n${stderr.take(2000)}"
        )
        return this
    }

    companion object {
        private const val IMAGE_TAG = "tc-mcp-gemini-agent:test"
        private const val CONTAINER_NAME = "tc-mcp-e2e-gemini"

        private fun findApiKey(): String? =
            System.getProperty("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
                ?: System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
    }
}
