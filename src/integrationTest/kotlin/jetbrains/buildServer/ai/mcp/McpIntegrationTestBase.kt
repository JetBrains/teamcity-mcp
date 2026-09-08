package jetbrains.buildServer.ai.mcp

import jetbrains.buildServer.ai.mcp.framework.BraveModeControl
import jetbrains.buildServer.ai.mcp.framework.TcServerConfig
import jetbrains.buildServer.ai.mcp.framework.TestMcpClient
import kotlinx.serialization.json.*
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Base class for MCP integration tests.
 *
 * Reads the target server from system properties or environment variables:
 *   - `TC_SERVER_URL`   — e.g. "http://localhost:8111"  (required)
 *   - `TC_SERVER_TOKEN` — permanent Bearer token         (required)
 *   - `TC_SERVER_RESTRICTED_TOKEN` — permission-restricted Bearer token (permission tests only)
 *   - `TC_DATA_PATH`    — TeamCity data directory        (brave-mode tests only)
 *
 * Run with:
 * ```
 * ./gradlew integrationTest -DTC_SERVER_URL=http://localhost:8111 -DTC_SERVER_TOKEN=eyJ...
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class McpIntegrationTestBase {

    protected val seededPipelineName = "MCP Seeded Pipeline"

    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    val serverConfig: TcServerConfig by lazy {
        val url   = prop("TC_SERVER_URL")   ?: error("TC_SERVER_URL system property or env var is required")
        val token = prop("TC_SERVER_TOKEN") ?: error("TC_SERVER_TOKEN system property or env var is required")
        TcServerConfig(baseUrl = url, bearerToken = token)
    }

    /** Creates a fresh [TestMcpClient] bound to the configured server. */
    fun mcpClient(): TestMcpClient = TestMcpClient(serverConfig)

    /**
     * Creates a fresh [TestMcpClient] authenticated with the permission-restricted token
     * (`TC_SERVER_RESTRICTED_TOKEN`) — an admin token limited to `view_project` on the
     * `McpPermissionVisible` fixture, see `scripts/setup-test-server.sh`.
     */
    fun restrictedMcpClient(): TestMcpClient {
        val restrictedToken = prop("TC_SERVER_RESTRICTED_TOKEN")
            ?: error("TC_SERVER_RESTRICTED_TOKEN system property or env var is required")
        return TestMcpClient(TcServerConfig(baseUrl = serverConfig.baseUrl, bearerToken = restrictedToken))
    }

    /**
     * Flips `teamcity.ai.mcp.braveMode.enabled` and waits until the MCP tool list reflects
     * the change. Requires `TC_DATA_PATH` — call [BraveModeControl.assumeAvailable] first.
     */
    protected fun setBraveMode(enabled: Boolean) {
        if (BraveModeControl.readBraveMode() != enabled.toString()) {
            BraveModeControl.setBraveMode(enabled)
        }
        awaitToolVisibility(BRAVE_ONLY_TOOL, enabled)
    }

    /**
     * Polls the MCP tool list until [toolName]'s visibility matches [expectVisible].
     * Accounts for TeamCity's internal-properties refresh latency (~5-10s).
     */
    protected fun awaitToolVisibility(toolName: String, expectVisible: Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            val visible = mcpClient().use { client ->
                var seen = false
                client.withSession { seen = listTools().any { it.name == toolName } }
                seen
            }
            if (visible == expectVisible) return
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "'$toolName' visibility did not reach $expectVisible within 30s. " +
                        "Check that TC is reloading internal.properties from TC_DATA_PATH."
                )
            }
            Thread.sleep(1000)
        }
    }

    /** Unwraps the `body` object from the JSON envelope the REST tools return. */
    protected fun extractBody(result: TestMcpClient.ToolResult): JsonObject {
        val text = result.content.first().text
        val envelope = Json.parseToJsonElement(text).jsonObject
        return envelope["body"]?.jsonObject ?: JsonObject(emptyMap())
    }

    protected fun ensureSeededPipeline(): String {
        val existing = listPipelines().firstOrNull {
            it["name"]?.jsonPrimitive?.content == seededPipelineName
        }
        if (existing != null) {
            return existing["id"]?.jsonPrimitive?.content ?: seededPipelineName
        }

        val payload = buildJsonObject {
            put("name", seededPipelineName)
            put("yaml", "jobs:\n  Job1:\n    name: Seed Job\n    runs-on: Linux-Medium\n    steps: []\n")
            put("additionalVcsRoots", buildJsonArray { })
            put("triggers", buildJsonArray { })
            put("integrations", buildJsonArray { })
            put("notifications", buildJsonArray { })
        }

        val response = sendTeamCityRequest("/app/pipeline", "POST", payload.toString())
        check(response.statusCode() in 200..299) {
            "Failed to create seeded pipeline fixture: HTTP ${response.statusCode()} body=${response.body()}"
        }

        val created = json.parseToJsonElement(response.body()).jsonObject
        return created["id"]?.jsonPrimitive?.content ?: seededPipelineName
    }

    protected fun listPipelines(): List<JsonObject> {
        val response = sendTeamCityRequest("/app/pipeline")
        check(response.statusCode() in 200..299) {
            "Failed to list pipelines: HTTP ${response.statusCode()} body=${response.body()}"
        }

        return when (val parsed = json.parseToJsonElement(response.body())) {
            is JsonArray -> parsed.map { it.jsonObject }
            is JsonObject -> parsed["items"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
            else -> emptyList()
        }
    }

    protected fun createPipeline(name: String, yaml: String = "jobs:\n  Job1:\n    name: Seed Job\n    steps: []\n"): String {
        val payload = buildJsonObject {
            put("name", name)
            put("yaml", yaml)
            put("additionalVcsRoots", buildJsonArray { })
            put("triggers", buildJsonArray { })
            put("integrations", buildJsonArray { })
            put("notifications", buildJsonArray { })
        }

        val response = sendTeamCityRequest("/app/pipeline", "POST", payload.toString())
        check(response.statusCode() in 200..299) {
            "Failed to create pipeline '$name': HTTP ${response.statusCode()} body=${response.body()}"
        }

        return json.parseToJsonElement(response.body()).jsonObject["id"]?.jsonPrimitive?.content
            ?: error("Pipeline creation response missing 'id'")
    }

    protected fun pipelineExists(pipelineId: String): Boolean {
        return listPipelines().any { it["id"]?.jsonPrimitive?.content == pipelineId }
    }

    protected fun projectExists(projectId: String): Boolean {
        val response = sendTeamCityRequest("/app/rest/projects/id:$projectId")
        return response.statusCode() in 200..299
    }

    protected fun createProject(projectId: String, parentProjectId: String = "_Root") {
        val payload = buildJsonObject {
            put("id", projectId)
            put("name", projectId)
            put("parentProject", buildJsonObject { put("id", parentProjectId) })
        }
        val response = sendTeamCityRequest("/app/rest/projects", "POST", payload.toString())
        check(response.statusCode() in 200..299) {
            "Failed to create project '$projectId': HTTP ${response.statusCode()} body=${response.body()}"
        }
    }

    protected fun deleteProject(projectId: String) {
        val response = sendTeamCityRequest("/app/rest/projects/id:$projectId", "DELETE")
        check(response.statusCode() in 200..299 || response.statusCode() == 404) {
            "Failed to delete project '$projectId': HTTP ${response.statusCode()} body=${response.body()}"
        }
    }

    protected fun prop(name: String): String? = System.getProperty(name) ?: System.getenv(name)

    private fun sendTeamCityRequest(
        path: String,
        method: String = "GET",
        body: String? = null
    ): HttpResponse<String> {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("${serverConfig.baseUrl}$path"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${serverConfig.bearerToken}")
            .header("Accept", "application/json")

        if (body != null) {
            requestBuilder.header("Content-Type", "application/json")
        }

        when (method) {
            "GET" -> requestBuilder.GET()
            "POST" -> requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
            "DELETE" -> requestBuilder.DELETE()
            else -> error("Unsupported method: $method")
        }

        return http.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        /**
         * A tool that exists only while brave mode is on — used as the probe for
         * [setBraveMode]. Tool names are `internal` in the plugin module, so tests
         * spell them out.
         */
        private const val BRAVE_ONLY_TOOL = "teamcity_rest_delete"
    }
}
