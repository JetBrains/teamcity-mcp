package jetbrains.buildServer.ai.mcp.tests.e2e

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import jetbrains.buildServer.ai.mcp.framework.e2e.FusLogAssertions
import jetbrains.buildServer.ai.mcp.framework.e2e.ScriptRunner
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue

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
        val output = scripts.runWithRetry("claude-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "List all available MCP tools. For each tool print exactly one line:\n" +
                    "TOOL: <name> - <description>\n" +
                    "Do not call any tool. Only list them.",
                findApiKey()!!))

        output.dump("tool discovery")
            .assumeExternalApiAvailable("tool discovery")
            .assertNoErrors()
            .assertExitCode(0, "tool discovery")
            .assertOutputContains("introduce_yourself", "tool listing must include introduce_yourself")
    }

    @Test
    fun `agent calls introduce_yourself tool and gets a response`() {
        val output = scripts.runWithRetry("claude-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "Call the MCP tool named \"introduce_yourself\" exactly once with name \"Claude\".\n" +
                    "Print the tool's text result on a line starting with:\n" +
                    "RESULT: <the text returned by the tool>",
                findApiKey()!!))

        output.dump("tool call")
            .assumeExternalApiAvailable("tool call")
            .assertNoErrors()
            .assertExitCode(0, "tool call")
            .assertToolCalled("introduce_yourself")
            .assertOutputContains("RESULT:")

        FusLogAssertions.assertFusEventsLogged(
            "ai.mcp.session.requested",
            "ai.mcp.session.started",
            "ai.mcp.session.message.received",
            message = "Claude agent FUS events"
        )
    }

    @Test
    fun `agent can list tools and call one in a single turn`() {
        val output = scripts.runWithRetry("claude-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "1. List all available MCP tools. Print each as: TOOL: <name>\n" +
                    "2. Then call \"introduce_yourself\" tool.\n" +
                    "3. Print the result as: RESULT: <text>",
                findApiKey()!!))

        output.dump("combined prompt")
            .assumeExternalApiAvailable("combined prompt")
            .assertNoErrors()
            .assertExitCode(0, "combined prompt")
            .assertOutputContains("TOOL:")
            .assertOutputContains("RESULT:")
            .assertToolCalled("introduce_yourself")
    }

    @Test
    fun `agent retrieves server info via REST tool`() {
        val output = scripts.runWithRetry("claude-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "Use the teamcity_rest_get tool to call /app/rest/server with query fields=version,buildNumber.\n" +
                    "Print the result on a line starting with:\n" +
                    "SERVER: version=<version> build=<buildNumber>",
                findApiKey()!!))

        output.dump("rest tool")
            .assumeExternalApiAvailable("rest tool")
            .assertNoErrors()
            .assertExitCode(0, "rest tool")
            .assertToolCalled("teamcity_rest_get")
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

        val output = scripts.runWithRetry("claude-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "Use the teamcity_pipeline_get tool to call path /app/pipeline with no query.\n" +
                    "Then print one line starting with:\n" +
                    "PIPELINES: <short summary of what was returned>\n" +
                    "The summary must explicitly mention the pipeline named \"" + seededPipelineName + "\" if it is present.\n" +
                    "If the response is a JSON array, include how many pipeline objects it contains.",
                findApiKey()!!))

        output.dump("pipeline tool")
            .assumeExternalApiAvailable("pipeline tool")
            .assertNoErrors()
            .assertExitCode(0, "pipeline tool")
            .assertToolCalled("teamcity_pipeline_get")
            .assertOutputContains("PIPELINES:")
            .assertOutputContains(seededPipelineName, "agent must report the seeded pipeline name")
    }

    @Test
    fun `agent calls common REST endpoints including encoding-sensitive queries`() {
        val output = scripts.runWithRetry("claude-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                """You must call the teamcity_rest_get tool for EACH of the following queries, one call per query. Do NOT skip any.
After each call, print exactly one result line.

1. path=/app/rest/server query=fields=version,buildNumber
   Print: SERVER: <version>

2. path=/app/rest/projects query=fields=project(id,name)
   Print: PROJECTS: <count> projects

3. path=/app/rest/buildTypes query=fields=buildType(id,name)
   Print: BUILD_TYPES: <count> configs

4. path=/app/rest/builds query=locator=defaultFilter:true,count:3&fields=build(id,status)
   Print: BUILDS: <count> builds

5. path=/app/rest/agents query=fields=agent(id,name,connected)
   Print: AGENTS: <count> agents

6. path=/app/rest/builds query=locator=buildType:(id:Test_Test),sinceDate:20240101T000000+0000,count:3&fields=build(id,number)
   Print: SINCE_DATE: <count> builds (this tests + encoding in dates)

At the end, print DONE.""",
                findApiKey()!!),
            timeoutSeconds = 300)

        output.dump("common REST endpoints")
            .assumeExternalApiAvailable("common REST endpoints")
            .assertExitCode(0, "common REST endpoints")
            .assertToolCalled("teamcity_rest_get")
            .assertOutputContainsAny(
                "SERVER:", "PROJECTS:", "BUILD_TYPES:", "BUILDS:", "AGENTS:",
                message = "agent must report results from REST endpoint calls"
            )
            .assertOutputContains("DONE")
    }

    @Test
    fun `agent discovers TeamCity MCP resources`() {
        val output = scripts.runWithRetry("claude-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "You have access to MCP resources (read-only data) in addition to tools. " +
                    "List all MCP resources available to you. " +
                    "For each resource include its URI and name in your response.",
                findApiKey()!!))

        output.dump("resource discovery")
            .assumeExternalApiAvailable("resource discovery")
            .assertExitCode(0, "resource discovery")
            .assertOutputContainsAny(
                "teamcity://", "introduce-yourself", "introduce_yourself",
                "rest-api", "rest_api_guide",
                message = "output must mention at least one resource URI or name"
            )
    }

    @Test
    fun `agent reads introduce_yourself resource`() {
        val output = scripts.runWithRetry("claude-agent.sh",
            listOf("run-prompt", CONTAINER_NAME,
                "You have access to MCP resources. " +
                    "Read the MCP resource with URI \"teamcity://info/introduce-yourself\" " +
                    "and include its full content in your response.",
                findApiKey()!!))

        output.dump("resource read")
            .assumeExternalApiAvailable("resource read")
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
