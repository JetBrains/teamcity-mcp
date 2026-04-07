package jetbrains.buildServer.ai.mcp.tests.e2e

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import jetbrains.buildServer.ai.mcp.framework.e2e.AgentOutput
import jetbrains.buildServer.ai.mcp.framework.e2e.ScriptRunner
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * End-to-end tests that run JetBrains Junie inside a Docker container against
 * the TeamCity MCP server.
 *
 * Approach:
 *  - A Docker image with Junie CLI is built once per test class
 *  - A container runs with `sleep infinity`; prompts are executed via `docker exec`
 *  - JUNIE_API_KEY is injected via `docker exec -e` at runtime and also passed via
 *    `--auth` flag. The bearer token is written to `.junie/mcp/mcp.json` in the
 *    container for MCP auth headers. The container is ephemeral and force-removed
 *    in tearDown.
 *
 * Junie quirks handled:
 *  - Junie outputs a single JSON summary (not NDJSON events), so tool calls cannot
 *    be verified structurally — we verify via output content instead.
 *  - Junie may not support MCP resources in headless mode; resource tests are probed
 *    and skipped if not supported.
 *  - Junie may not exit cleanly; exit codes 124 (timeout) and -1 (ScriptRunner timeout)
 *    are handled when the result text contains expected content.
 *
 * Prerequisites:
 *  - Docker daemon running
 *  - JUNIE_API_KEY environment variable or system property set
 *  - TeamCity server running with MCP plugin deployed
 *
 * Run with:
 * ```
 * ./gradlew e2eTest -DJUNIE_API_KEY=...
 * ```
 *
 * Skipped automatically when Docker is unavailable or API key is missing.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JunieAgentE2eTest : McpIntegrationTestBase() {

    private val scripts = ScriptRunner(ScriptRunner.scriptsDir())

    /** Whether the agent exposes MCP resource tools (detected during setUp). */
    private var supportsResources = false

    @BeforeAll
    fun setUp() {
        scripts.runChecked("common.sh", listOf("check-docker"))
        assertNotNull(findApiKey(), "JUNIE_API_KEY not set")

        val dockerfileDir = System.getProperty("user.dir") + "/src/integrationTest/docker/junie-agent"
        println("  Building Docker image $IMAGE_TAG ...")
        scripts.runChecked("common.sh", listOf("build", dockerfileDir, IMAGE_TAG), timeoutSeconds = 600)

        scripts.runChecked("common.sh", listOf("start", CONTAINER_NAME, IMAGE_TAG))
        scripts.runChecked("junie-agent.sh", listOf("configure", CONTAINER_NAME, serverConfig.mcpUrl, serverConfig.bearerToken))
        println("  Container $CONTAINER_NAME started, MCP configured for ${serverConfig.mcpUrl}")

        // Quick probe: run a simple prompt to check if resources are supported.
        val probe = scripts.run("junie-agent.sh",
            listOf("run-prompt", CONTAINER_NAME, "Say hello.", findApiKey()!!),
            timeoutSeconds = 120)
        val probeText = (probe.stdout + probe.fullText).lowercase()
        supportsResources = "resource" in probeText && "teamcity://" in probeText
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

    /**
     * Tool discovery is covered by [agent can list tools and call one in a single turn]
     * which lists tools AND calls one in ~15s. A standalone "list only" prompt triggers
     * excessive Junie orchestration overhead (4 LLM calls, 200s+), so we skip it here.
     */
    @Test
    fun `agent discovers TeamCity MCP tools`() {
        // Reuse the combined prompt result — Junie's orchestration is too slow for
        // a dedicated list-only prompt. The combined test already validates tool listing.
        val output = scripts.run("junie-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "Print all MCP tool names, one per line as: TOOL: <name>\n" +
                    "Then call \"introduce_yourself\" with name \"Junie\" and print: DONE",
                findApiKey()!!))

        output.dump("tool discovery")
            .assertJunieSuccess("tool discovery")
            .assertOutputContainsAny(
                "introduce_yourself", "mcp_teamcity_introduce_yourself",
                "teamcity_rest_get", "mcp_teamcity_teamcity_rest_get",
                "TOOL:", "MCP tools",
                message = "tool listing must reference MCP tools"
            )
    }

    @Test
    fun `agent calls introduce_yourself tool and gets a response`() {
        val output = scripts.run("junie-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "Call the MCP tool named \"introduce_yourself\" exactly once with name \"Junie\".\n" +
                    "Print the tool's text result on a line starting with:\n" +
                    "RESULT: <the text returned by the tool>",
                findApiKey()!!))

        output.dump("tool call")
            .assertJunieSuccess("tool call")
            .assertOutputContains("RESULT:")
            .assertOutputContainsAny(
                "Hello, Junie", "TeamCity MCP server",
                message = "agent must have called introduce_yourself and received a response"
            )
    }

    @Test
    fun `agent can list tools and call one in a single turn`() {
        val output = scripts.run("junie-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "1. List all available MCP tools. Print each as: TOOL: <name>\n" +
                    "2. Then call \"introduce_yourself\" tool.\n" +
                    "3. Print the result as: RESULT: <text>",
                findApiKey()!!))

        output.dump("combined prompt")
            .assertJunieSuccess("combined prompt")
            .assertOutputContains("TOOL:")
            .assertOutputContains("RESULT:")
    }

    @Test
    fun `agent retrieves server info via REST tool`() {
        val output = scripts.run("junie-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "Use the teamcity_rest_get tool to call /app/rest/server with query fields=version,buildNumber.\n" +
                    "Print the result on a line starting with:\n" +
                    "SERVER: version=<version> build=<buildNumber>",
                findApiKey()!!))

        output.dump("rest tool")
            .assertJunieSuccess("rest tool")
            .assertOutputContainsAny(
                "SERVER:", "version",
                message = "agent must report server info retrieved via REST tool"
            )
    }

    @Test
    fun `agent uses pipeline get tool when pipeline support is enabled`() {
        mcpClient().use { client ->
            client.withSession {
                val toolNames = listTools().map { it.name }.toSet()
                assumeTrue(
                    "teamcity_pipeline_get" in toolNames,
                    "Pipeline tool support is not enabled on this server (tools: $toolNames) — skipping"
                )
                ensureSeededPipeline()
            }
        }

        val output = scripts.run("junie-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "Use the teamcity_pipeline_get tool to call path /app/pipeline with no query.\n" +
                    "Then print one line starting with:\n" +
                    "PIPELINES: <short summary of what was returned>\n" +
                    "The summary must explicitly mention the pipeline named \"" + seededPipelineName + "\" if it is present.\n" +
                    "If the response is a JSON array, include how many pipeline objects it contains.",
                findApiKey()!!))

        output.dump("pipeline tool")
            .assertJunieSuccess("pipeline tool")
            .assertOutputContains("PIPELINES:")
            .assertOutputContains(seededPipelineName, "agent must report the seeded pipeline name")
    }

    @Test
    fun `agent discovers TeamCity MCP resources`() {
        assumeTrue(supportsResources,
            "Junie CLI does not expose MCP resource tools in headless mode — skipping")

        val output = scripts.run("junie-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "You have access to MCP resources (read-only data) in addition to tools. " +
                    "List all MCP resources available to you. " +
                    "For each resource include its URI and name in your response.",
                findApiKey()!!))

        output.dump("resource discovery")
            .assertJunieSuccess("resource discovery")
            .assertOutputContainsAny(
                "teamcity://", "introduce-yourself", "introduce_yourself",
                "rest-api", "rest_api_guide",
                message = "output must mention at least one resource URI or name"
            )
    }

    @Test
    fun `agent reads introduce_yourself resource`() {
        assumeTrue(supportsResources,
            "Junie CLI does not expose MCP resource tools in headless mode — skipping")

        val output = scripts.run("junie-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "You have access to MCP resources. " +
                    "Read the MCP resource with URI \"teamcity://info/introduce-yourself\" " +
                    "and include its full content in your response.",
                findApiKey()!!))

        output.dump("resource read")
            .assertJunieSuccess("resource read")
            .assertOutputContainsAny(
                "TeamCity MCP server", "tools and resources", "AI agents",
                message = "output must contain content from the introduce_yourself resource"
            )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Junie outputs a single JSON summary — not an NDJSON event stream.
     * A successful run has exit code 0 and a non-empty `result` field.
     *
     * Junie may not exit cleanly after processing — the MCP connection keeps it
     * alive. The shell script wraps it with `timeout 120`, so exit 124 (SIGTERM)
     * or 137 (SIGKILL) are expected when the JSON output already contains a result.
     *
     * Junie may also fail to connect to LLM on transient errors (exit 1 with
     * "Unable to connect to LLM service") — we treat these as test assumptions.
     */
    private fun AgentOutput.assertJunieSuccess(label: String): AgentOutput {
        if (exitCode == 0) return this
        // Killed by timeout but output contains result — treat as success
        if (exitCode in listOf(124, 137) && stdout.contains("\"result\"")) {
            return this
        }
        // Transient LLM connectivity failures — skip rather than fail
        val combined = stdout + stderr
        if (exitCode == 1 && ("Unable to connect to LLM" in combined || "Failed to build" in combined)) {
            assumeTrue(false,
                "$label: Junie LLM connectivity issue (transient) — skipping. stderr: ${stderr.take(500)}")
        }
        Assertions.fail<Unit>(
            "$label exited with $exitCode (expected 0, or 124/137 with result output).\nstderr:\n${stderr.take(2000)}")
        return this
    }

    companion object {
        private const val IMAGE_TAG = "tc-mcp-junie-agent:test"
        private const val CONTAINER_NAME = "tc-mcp-e2e-junie"

        private fun findApiKey(): String? =
            System.getProperty("JUNIE_API_KEY")?.takeIf { it.isNotBlank() }
                ?: System.getenv("JUNIE_API_KEY")?.takeIf { it.isNotBlank() }
    }
}
