package jetbrains.buildServer.ai.mcp.tools.rest

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.ai.mcp.BUILD_QUEUE_PATH
import jetbrains.buildServer.ai.mcp.MCP_REST_POST_ALLOWED_PATHS
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

/**
 * Brave-mode variant of the REST POST tool. Shares the tool name [RestPostTool.NAME]
 * (`teamcity_rest_post`) so that MCP clients see the same tool name regardless of mode —
 * the server's configurator selects this bean when brave mode is on and the safe
 * [RestPostTool] otherwise.
 *
 * Differences from [RestPostTool]:
 *  - Default allowlist is "allow all" (no `{buildQueue}` restriction).
 *  - Does NOT force `"personal": true` in the request body.
 *  - Does NOT inject a default `comment` for buildQueue requests.
 */
@Component
class RestPostBraveTool(
    @Autowired(required = false) private val restApiClient: RestApiClient? = null,
    private val settingsService: SettingsService
) : BraveModeAwareMcpTool {

    override val brave = true

    companion object {
        private val LOGGER = Logger.getInstance(RestPostBraveTool::class.java.name)

        private val ERROR_GUIDANCE = RestToolUtils.COMMON_ERROR_GUIDANCE + mapOf(
            400 to "Check the request body structure — it may be malformed or missing required fields.",
            409 to "Conflict — the request could not be completed due to a conflict with current state.",
            415 to "Unsupported media type — the endpoint's @Consumes does not match the body's inferred type."
        )
    }

    override val name = RestPostTool.NAME

    override val description = """
        |Perform a POST request to the TeamCity REST API.
        |
        |IMPORTANT: Read "teamcity://guides/rest-api" before using this tool.
        |
        |Before triggering a build to `$BUILD_QUEUE_PATH`, determine the target branch from the user's working context (current git branch via `git rev-parse --abbrev-ref HEAD`, the branch open in the IDE, or by asking). Pass it as `branchName` in the body. Without `branchName`, the build runs on the configuration's default branch — and because this tool does NOT enforce `"personal": true`, that build is real and team-visible. The response includes a warning note when `branchName` is missing.
        |
        |The body is passed through unchanged — nothing is injected or rewritten. A POST to `$BUILD_QUEUE_PATH` without `"personal": true` creates a real, team-visible queued build; include `"personal": true` in the body for an isolated run.
        |Allowed POST paths come from the `$MCP_REST_POST_ALLOWED_PATHS` property; when unset, all `/app/rest/...` POST paths are allowed.
        |
        |Response format: same JSON envelope as teamcity_rest_get — meta(url, statusCode, notes), contentType, body/bodyText.
    """.trimMargin()

    override val inputSchema = McpToolSchema(
        properties = buildJsonObject {
            putJsonObject("path") {
                put("type", "string")
                put(
                    "description", """
                    |REST API endpoint path starting with /app/rest/.
                    |Examples:
                    |  /app/rest/buildQueue                        — trigger a (non-personal) build — set `branchName` in the body
                    |  /app/rest/buildQueue/id:123/approve         — approve a queued build
                    |  /app/rest/builds/id:123/tags/               — add a tag
                    |  /app/rest/projects                          — create a project
                """.trimMargin()
                )
            }
            putJsonObject("body") {
                put("type", "string")
                put(
                    "description", """
                    |JSON request body. Must be a JSON object. Passed through unchanged — this tool does NOT enforce `"personal": true`, so a build trigger here is real and team-visible. Always set `branchName` when triggering a build (see Branch awareness in the tool description above).
                    |
                    |Example — trigger a build on a specific branch:
                    |  {"buildType":{"id":"MyBuildConfig_Build"},"branchName":"feature-x"}
                    |
                    |Example — personal build on a branch:
                    |  {"buildType":{"id":"MyBuildConfig_Build"},"branchName":"feature-x","personal":true}
                    |
                    |Example — add a tag (no branch concern; targets a specific build by id):
                    |  {"tag":[{"name":"release"}]}
                """.trimMargin()
                )
            }
            putJsonObject("query") {
                put("type", "string")
                put("description", "Optional query parameters without leading '?'. Combine with 'fields' or replace it.")
            }
            putJsonObject("fields") {
                put("type", "string")
                put("description", "Field selection for the response. Include `branchName` so the response confirms which branch the build was queued on, e.g. id,number,status,branchName.")
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
        RestToolUtils.checkAllowedPath("POST", normalizedPath, settingsService.getRestPostAllowedPaths())
            ?.let { return it }

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

        val query = buildQuery(
            rawQuery = arguments["query"]?.jsonPrimitive?.content?.trim()?.removePrefix("?"),
            fields = arguments["fields"]?.jsonPrimitive?.content?.trim()
        )

        return try {
            val response = client.post(normalizedPath, query, parsedBody.toString())
            val notes = mutableListOf<String>()
            if (normalizedPath == BUILD_QUEUE_PATH && !parsedBody.containsKey("branchName")) {
                notes.add(
                    "No `branchName` was provided — the build was queued on the configuration's default branch. " +
                            "If the user is working on a non-default branch, cancel via DELETE $BUILD_QUEUE_PATH/id:<returnedId> and retrigger with `branchName` in the body."
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
