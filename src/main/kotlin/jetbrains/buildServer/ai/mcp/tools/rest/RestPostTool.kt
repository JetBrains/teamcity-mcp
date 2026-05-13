package jetbrains.buildServer.ai.mcp.tools.rest

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.ai.mcp.BUILD_QUEUE_PATH
import jetbrains.buildServer.ai.mcp.SettingsService
import jetbrains.buildServer.ai.mcp.tools.BraveModeAwareMcpTool
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
) : BraveModeAwareMcpTool {

    override val brave = false

    companion object {
        private val LOGGER = Logger.getInstance(RestPostTool::class.java.name)

        internal const val NAME = "teamcity_rest_post"

        private val ERROR_GUIDANCE = RestToolUtils.COMMON_ERROR_GUIDANCE + mapOf(
            400 to "Check the request body structure — it may be malformed or missing required fields.",
            409 to "Conflict — the request could not be completed due to a conflict with current state.",
            415 to "Unsupported media type — the endpoint's @Consumes does not match the body's inferred type."
        )
    }

    override val name = NAME

    override val description = """
        |Perform a POST request to the TeamCity REST API.
        |
        |IMPORTANT: Before your first use, read the resource "teamcity://guides/rest-api" (Part 2) for guidance on triggering builds, request body format, and monitoring workflows.
        |
        |Before triggering a build to $BUILD_QUEUE_PATH, determine the target branch from the user's working context (current git branch via `git rev-parse --abbrev-ref HEAD`, the branch open in the IDE, or by asking). Pass it as `branchName` in the body. Without `branchName`, the build runs on the configuration's default branch — rarely what the user actually wants. The response includes a warning note when `branchName` is missing.
        |
        |Limited to allowed endpoints (default: $BUILD_QUEUE_PATH). All requests are enforced as personal builds — the tool automatically sets `"personal": true` in the request body.
        |Use this for safe, isolated actions such as queueing a personal build and then inspecting it with teamcity_rest_get and teamcity_build_log.
        |
        |Response format: same JSON envelope as teamcity_rest_get — meta(url, statusCode, notes), contentType, body/bodyText.
        |
        |Example — trigger a personal build on a specific branch:
        |  path: $BUILD_QUEUE_PATH
        |  body: {"buildType":{"id":"MyBuildConfig_Build"},"branchName":"feature-x"}
        |  query: moveToTop=true
        |  fields: id,number,status,personal,branchName,buildType(id,name)
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
                    |JSON request body. Must be a JSON object. The tool automatically enforces `"personal": true`. Always set `branchName` (see Branch awareness in the tool description above).
                    |
                    |Preferred (with branch):
                    |  {"buildType":{"id":"MyBuildConfig_Build"},"branchName":"feature-branch"}
                    |
                    |Minimal (only when the user explicitly wants the default branch):
                    |  {"buildType":{"id":"MyBuildConfig_Build"}}
                    |
                    |With comment:
                    |  {"buildType":{"id":"MyBuildConfig_Build"},"branchName":"feature-branch","comment":{"text":"Triggered by AI agent"}}
                """.trimMargin()
                )
            }
            putJsonObject("fields") {
                put("type", "string")
                put(
                    "description", """
                    |Field selection for the response. Include `branchName` so the response confirms which branch the build was queued on.
                    |Example: id,number,status,personal,branchName,buildType(id,name)
                """.trimMargin()
                )
            }
            putJsonObject("query") {
                put("type", "string")
                put(
                    "description", """
                    |Optional query parameters without leading '?'. Use for endpoint-specific flags and extra response shaping.
                    |Example: moveToTop=true
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

        RestToolUtils.validatePath(path)?.let { return it }

        val normalizedPath = path.trimEnd('/')
        val allowedPaths = settingsService.getRestPostAllowedPaths() ?: setOf(BUILD_QUEUE_PATH)
        RestToolUtils.checkAllowedPath("POST", normalizedPath, allowedPaths)?.let { return it }

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

        val query = buildQuery(
            rawQuery = arguments["query"]?.jsonPrimitive?.content?.trim()?.removePrefix("?"),
            fields = arguments["fields"]?.jsonPrimitive?.content?.trim()
        )

        return try {
            val response = client.post(normalizedPath, query, enforcedBody.toString())
            val notes = mutableListOf("\"personal\": true was enforced in the request body.")
            if (normalizedPath == BUILD_QUEUE_PATH && !parsedBody.containsKey("branchName")) {
                notes.add(
                    "No `branchName` was provided — the personal build was queued on the configuration's default branch. " +
                            "If the user is working on a non-default branch, retrigger with `branchName` in the body."
                )
            }
            RestToolUtils.formatResponse(normalizedPath, query, response, notes = notes)
        } catch (e: RestApiException) {
            LOGGER.warnAndDebugDetails("REST POST $normalizedPath failed with HTTP ${e.statusCode}", e)
            McpToolResult.error(RestToolUtils.formatRestApiError(e, ERROR_GUIDANCE))
        } catch (e: Exception) {
            LOGGER.warnAndDebugDetails("REST POST $normalizedPath failed", e)
            McpToolResult.error("REST request failed: ${e.message}")
        }
    }

    private fun buildQuery(rawQuery: String?, fields: String?): String {
        val parts = mutableListOf<String>()
        if (!rawQuery.isNullOrBlank()) parts.add(rawQuery)
        if (!fields.isNullOrBlank() && rawQuery?.contains("fields=") != true) {
            parts.add("fields=$fields")
        }
        return parts.joinToString("&")
    }
}
