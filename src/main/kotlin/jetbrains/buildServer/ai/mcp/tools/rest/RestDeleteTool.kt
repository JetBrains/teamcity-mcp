package jetbrains.buildServer.ai.mcp.tools.rest

import com.intellij.openapi.diagnostic.Logger
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
class RestDeleteTool(
    @Autowired(required = false) private val restApiClient: RestApiClient? = null,
    private val settingsService: SettingsService
) : McpTool {

    companion object {
        private val LOGGER = Logger.getInstance(RestDeleteTool::class.java.name)

        internal const val NAME = "teamcity_rest_delete"

        private val ERROR_GUIDANCE = RestToolUtils.COMMON_ERROR_GUIDANCE + mapOf(
            400 to "Check the path syntax — the locator may be malformed.",
            409 to "Conflict — the resource cannot be deleted in its current state."
        )
    }

    override val name = NAME

    override val description = """
        |Perform a DELETE request to the TeamCity REST API.
        |
        |IMPORTANT: Read "teamcity://guides/rest-api" before using this tool — see the DELETE section.
        |DELETE operations are often irreversible (project/buildType/vcs-root deletion, tag removal, investigation resolution, agent unregistration). Always confirm destructive actions with the user before executing.
        |All `/app/rest/...` DELETE paths are allowed — no allowlist.
        |Typical agent tasks: remove a queued build, clear comments or tags, resolve investigations, unmute failures, or unregister inactive agents.
        |
        |Response format: same JSON envelope as teamcity_rest_get — meta(url, statusCode, notes), contentType, body/bodyText. Successful deletion typically returns an empty body.
    """.trimMargin()

    override val inputSchema = McpToolSchema(
        properties = buildJsonObject {
            putJsonObject("path") {
                put("type", "string")
                put(
                    "description", """
                    |REST API endpoint path starting with /app/rest/.
                    |Examples:
                    |  /app/rest/buildQueue/id:12345             — cancel queued build
                    |  /app/rest/builds/id:12345/tags/flaky      — remove a tag
                    |  /app/rest/buildTypes/id:BT1               — delete build configuration (destructive)
                    |  /app/rest/projects/id:MyProject           — delete project and subprojects (destructive)
                    |  /app/rest/investigations/id:123           — resolve investigation
                    |  /app/rest/mutes/id:42                     — remove mute
                """.trimMargin()
                )
            }
            putJsonObject("query") {
                put("type", "string")
                put("description", "Optional query parameters without leading '?'.")
            }
        },
        required = listOf("path")
    )

    override suspend fun execute(arguments: JsonObject?): McpToolResult {
        if (!settingsService.isBraveModeEnabled()) {
            return McpToolResult.error("Brave mode must be enabled to use $NAME")
        }

        val path = arguments?.get("path")?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return McpToolResult.error("'path' parameter is required")

        RestToolUtils.validatePath(path)?.let { return it }

        val normalizedPath = path.trimEnd('/')

        val client = restApiClient
            ?: return McpToolResult.error("REST API client is not configured")

        val query = arguments["query"]?.jsonPrimitive?.content?.trim()?.removePrefix("?") ?: ""

        return try {
            val response = client.delete(normalizedPath, query)
            RestToolUtils.formatResponse(normalizedPath, query, response)
        } catch (e: RestApiException) {
            LOGGER.warnAndDebugDetails("REST DELETE $normalizedPath failed with HTTP ${e.statusCode}", e)
            McpToolResult.error(RestToolUtils.formatRestApiError(e, ERROR_GUIDANCE))
        } catch (e: Exception) {
            LOGGER.warnAndDebugDetails("REST DELETE $normalizedPath failed", e)
            McpToolResult.error("REST request failed: ${e.message}")
        }
    }
}
