package jetbrains.buildServer.ai.mcp

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

    private fun prop(name: String): String? = System.getProperty(name) ?: System.getenv(name)

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
            else -> error("Unsupported method: $method")
        }

        return http.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
