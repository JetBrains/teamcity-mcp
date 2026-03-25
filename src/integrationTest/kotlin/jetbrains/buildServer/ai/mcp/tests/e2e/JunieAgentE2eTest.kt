package jetbrains.buildServer.ai.mcp.tests.e2e

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import jetbrains.buildServer.ai.mcp.framework.e2e.AgentOutput
import jetbrains.buildServer.ai.mcp.framework.e2e.FusLogAssertions
import jetbrains.buildServer.ai.mcp.framework.e2e.ScriptRunner
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestMethodOrder

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
 * Junie quirks:
 *  - Outputs a single JSON summary `{"sessionId","taskName","result","changes","llmUsage"}`
 *    (not NDJSON), so tool calls cannot be verified structurally.
 *  - Uses multi-model orchestration (Gemini + GPT), making each prompt 4-12 LLM calls.
 *    To minimize cost and flakiness, only 3 tests run (one per distinct MCP tool).
 *  - Does not follow formatting instructions reliably — assertions use lenient matching.
 *  - Does not support MCP resources in headless mode.
 *  - The shell script wraps with `timeout 180`; exit 124/137 with no output → skip.
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
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class JunieAgentE2eTest : McpIntegrationTestBase() {

    private val scripts = ScriptRunner(ScriptRunner.scriptsDir())

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
    }

    @AfterAll
    fun tearDown() {
        scripts.run("common.sh", listOf("stop", CONTAINER_NAME))
        println("  Container $CONTAINER_NAME removed")
    }

    // -------------------------------------------------------------------------
    // Tests — one per distinct MCP tool, ordered simplest-first
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `agent retrieves server info via REST tool`() {
        val output = runJuniePrompt(
            "Use the teamcity_rest_get tool to call /app/rest/server with query fields=version,buildNumber.\n" +
                "Print the result on a line starting with:\n" +
                "SERVER: version=<version> build=<buildNumber>")

        output.dump("rest tool")
            .assumeJunieAvailable("rest tool")
            .assertJunieSuccess("rest tool")
            .assertOutputContainsAny(
                "SERVER:", "version", "buildNumber",
                message = "agent must report server info retrieved via REST tool"
            )
    }

    @Test
    @Order(2)
    fun `agent can list tools and call one in a single turn`() {
        val output = runJuniePrompt(
            "1. List all available MCP tools. Print each as: TOOL: <name>\n" +
                "2. Then call \"introduce_yourself\" tool with name \"Junie\".\n" +
                "3. Print the result as: RESULT: <text>")

        output.dump("combined prompt")
            .assumeJunieAvailable("combined prompt")
            .assertJunieSuccess("combined prompt")
            .assertOutputContainsAny(
                "introduce_yourself", "mcp_teamcity_introduce_yourself",
                "TOOL:", "MCP tools",
                message = "output must reference MCP tools"
            )
            .assertOutputContainsAny(
                "Hello, Junie", "TeamCity MCP server",
                message = "introduce_yourself tool must have been called and returned a response"
            )

        FusLogAssertions.assertFusEventsLogged(
            "ai.mcp.session.requested",
            "ai.mcp.session.started",
            "ai.mcp.session.message.received",
            message = "Junie agent FUS events"
        )
    }

    @Test
    @Order(3)
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

        val output = runJuniePrompt(
            "Use the teamcity_pipeline_get tool to call path /app/pipeline with no query.\n" +
                "Then print one line starting with:\n" +
                "PIPELINES: <short summary of what was returned>\n" +
                "The summary must explicitly mention the pipeline named \"" + seededPipelineName + "\" if it is present.\n" +
                "If the response is a JSON array, include how many pipeline objects it contains.")

        output.dump("pipeline tool")
            .assumeJunieAvailable("pipeline tool")
            .assertJunieSuccess("pipeline tool")
            .assertOutputContainsAny(
                "PIPELINES:", "pipeline", "Pipeline",
                message = "agent must report pipeline information"
            )
            .assertOutputContainsAny(
                seededPipelineName, "MCP Seeded", "Seeded Pipeline",
                message = "agent must mention the seeded pipeline by name"
            )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Run a Junie prompt with retry on timeout.
     *
     * Junie's multi-model orchestration frequently causes empty-output timeouts on CI.
     * Standard [ScriptRunner.runWithRetry] doesn't detect these because empty output
     * doesn't match any [AgentOutput.EXTERNAL_API_ERROR_PATTERNS].
     * This wrapper adds a Junie-specific retry for timeout-with-no-output.
     */
    private fun runJuniePrompt(prompt: String): AgentOutput {
        repeat(2) { attempt ->
            val output = scripts.runWithRetry("junie-agent.sh",
                listOf("run-prompt", CONTAINER_NAME, prompt, findApiKey()!!))
            if (output.exitCode in listOf(-1, 124, 137) && !output.stdout.contains("\"result\"")) {
                if (attempt == 0) {
                    println("  Junie timed out on attempt 1/2 (exit ${output.exitCode}, no output) — retrying...")
                    return@repeat
                }
            }
            return output
        }
        error("unreachable")
    }

    /**
     * Skip when Junie is unavailable: external API error, timeout with no output,
     * or transient LLM connectivity failure.
     *
     * Must be called before [assertJunieSuccess].
     */
    private fun AgentOutput.assumeJunieAvailable(label: String): AgentOutput {
        assumeExternalApiAvailable(label)
        if (exitCode in listOf(-1, 124, 137) && !stdout.contains("\"result\"")) {
            assumeTrue(false,
                "$label: Junie timed out with no output (exit $exitCode) — skipping")
        }
        val combined = stdout + stderr
        if (exitCode == 1 && ("Unable to connect to LLM" in combined || "Failed to build" in combined)) {
            assumeTrue(false,
                "$label: Junie LLM connectivity issue (transient) — skipping. stderr: ${stderr.take(500)}")
        }
        return this
    }

    /**
     * Assert that Junie produced a valid result.
     *
     * Accepts exit 0 (clean exit) or 124/137 (timeout kill) when the JSON output
     * already contains a `"result"` field — Junie sometimes hangs after completing
     * the prompt and gets killed by the timeout wrapper.
     */
    private fun AgentOutput.assertJunieSuccess(label: String): AgentOutput {
        if (exitCode == 0) return this
        if (exitCode in listOf(124, 137) && stdout.contains("\"result\"")) return this
        Assertions.fail<Unit>(
            "$label: Junie exited with $exitCode (expected 0, or 124/137 with result in output).\n" +
                "stdout: ${stdout.take(2000)}\nstderr: ${stderr.take(2000)}")
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
