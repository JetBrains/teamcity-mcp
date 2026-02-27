package jetbrains.buildServer.ai.mcp.tools.rest

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.ai.mcp.BUILD_QUEUE_PATH
import jetbrains.buildServer.ai.mcp.SettingsService
import jetbrains.buildServer.ai.mcp.tools.McpTool
import jetbrains.buildServer.ai.mcp.tools.McpToolResult
import jetbrains.buildServer.ai.mcp.tools.McpToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class RestPostTool(
    @Autowired(required = false) private val restApiClient: RestApiClient? = null,
    private val settingsService: SettingsService
) : McpTool {

    companion object {
        private val LOGGER = Logger.getInstance(RestPostTool::class.java.name)

        internal const val NAME = "teamcity_rest_post"

        private val ERROR_GUIDANCE = mapOf(
            400 to "Check the request body structure — it may be malformed or missing required fields.",
            403 to "Access denied. The current token may lack permission for this endpoint.",
            404 to "Resource not found. Check the path and request body — the referenced entity may not exist.",
            405 to "Method not allowed on this endpoint.",
            409 to "Conflict — the request could not be completed due to a conflict with current state.",
            500 to "TeamCity server error. Try again or simplify the request."
        )
    }

    override val name = NAME

    override val description = """
        |Perform a POST request to the TeamCity REST API.
        |
        |Currently limited to allowed endpoints (default: $BUILD_QUEUE_PATH).
        |All requests are enforced as personal builds — the tool automatically sets "personal": true in the request body.
        |
        |Parameters:
        |- path: REST API endpoint (must start with /app/rest/)
        |- body: JSON request body (must be a JSON object)
        |- fields: (optional) field selection for the response, e.g. "id,number,status"
        |
        |Response format:
        |- Same JSON envelope as teamcity_rest_get: meta(url, statusCode, notes), contentType, body/bodyText.
        |
        |Example — trigger a personal build:
        |  path: $BUILD_QUEUE_PATH
        |  body: {"buildType":{"id":"MyBuildConfig_Build"}}
        |  fields: id,number,status,personal,buildType(id,name)
    """.trimMargin()

    override val inputSchema = McpToolSchema(
        properties = buildJsonObject {
            putJsonObject("path") {
                put("type", "string")
                put(
                    "description", """
                    |REST API endpoint path starting with /app/rest/.
                    |Allowed endpoints are controlled by server configuration (default: $BUILD_QUEUE_PATH).
                """.trimMargin()
                )
            }
            putJsonObject("body") {
                put("type", "string")
                put(
                    "description", """
                    |JSON request body. Must be a JSON object.
                    |The tool automatically enforces "personal": true in the body.
                    |
                    |Example for triggering a build:
                    |  {"buildType":{"id":"MyBuildConfig_Build"}}
                    |
                    |With branch:
                    |  {"buildType":{"id":"MyBuildConfig_Build"},"branchName":"feature-branch"}
                    |
                    |With comment:
                    |  {"buildType":{"id":"MyBuildConfig_Build"},"comment":{"text":"Triggered by AI agent"}}
                """.trimMargin()
                )
            }
            putJsonObject("fields") {
                put("type", "string")
                put(
                    "description", """
                    |Field selection for the response. Controls which fields are returned.
                    |Example: id,number,status,personal,buildType(id,name)
                """.trimMargin()
                )
            }
        },
        required = listOf("path", "body")
    )

    override suspend fun execute(arguments: JsonObject?): McpToolResult {
        val path = arguments?.get("path")?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return McpToolResult.error("'path' parameter is required")

        RestToolUtils.validatePath(path, "fields")?.let { return it }

        val normalizedPath = path.trimEnd('/')
        val allowedPaths = settingsService.getRestPostAllowedPaths()
        if (normalizedPath !in allowedPaths) {
            return McpToolResult.error(
                "POST to '$normalizedPath' is not allowed. Allowed paths: ${allowedPaths.joinToString(", ")}"
            )
        }

        val bodyStr = arguments["body"]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return McpToolResult.error("'body' parameter is required and must be non-empty")

        val parsedBody = RestToolUtils.parseJsonOrNull(bodyStr)
            ?: return McpToolResult.error("'body' must be valid JSON")

        if (parsedBody !is JsonObject) {
            return McpToolResult.error("'body' must be a JSON object, not a JSON array or primitive")
        }

        val client = restApiClient
            ?: return McpToolResult.error("REST API client is not configured")

        // Enforce personal=true and default comment for buildQueue
        val enforcedBody = buildJsonObject {
            parsedBody.forEach { (key, value) ->
                if (key != "personal") put(key, value)
            }
            put("personal", true)
            if (normalizedPath == BUILD_QUEUE_PATH && !parsedBody.containsKey("comment")) {
                putJsonObject("comment") {
                    put("text", "Triggered via MCP")
                }
            }
        }

        val fields = arguments["fields"]?.jsonPrimitive?.content?.trim() ?: ""
        val query = if (fields.isNotBlank()) "fields=$fields" else ""

        return try {
            val response = client.post(normalizedPath, query, enforcedBody.toString())
            formatResponse(normalizedPath, query, response)
        } catch (e: RestApiException) {
            LOGGER.warnAndDebugDetails("REST POST $normalizedPath failed with HTTP ${e.statusCode}", e)
            McpToolResult.error(RestToolUtils.formatRestApiError(e, ERROR_GUIDANCE))
        } catch (e: Exception) {
            LOGGER.warnAndDebugDetails("REST POST $normalizedPath failed", e)
            McpToolResult.error("REST request failed: ${e.message}")
        }
    }

    private fun formatResponse(
        path: String,
        query: String,
        response: RestApiResponse
    ): McpToolResult {
        val url = if (query.isNotEmpty()) "$path?$query" else path
        val notes = mutableListOf<String>()

        notes.add("\"personal\": true was enforced in the request body.")

        return RestToolUtils.buildResponseJson(
            url = url,
            statusCode = response.statusCode,
            responseBody = response.body,
            notes = notes
        )
    }
}
