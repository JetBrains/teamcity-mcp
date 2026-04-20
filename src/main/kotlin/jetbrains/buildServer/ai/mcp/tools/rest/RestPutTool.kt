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
class RestPutTool(
    @Autowired(required = false) private val restApiClient: RestApiClient? = null,
    private val settingsService: SettingsService
) : McpTool {

    companion object {
        private val LOGGER = Logger.getInstance(RestPutTool::class.java.name)

        internal const val NAME = "teamcity_rest_put"

        private val ERROR_GUIDANCE = RestToolUtils.COMMON_ERROR_GUIDANCE + mapOf(
            400 to "Check the request body structure — it may be malformed or missing required fields.",
            409 to "Conflict — the request could not be completed due to a conflict with current state.",
            415 to "Unsupported media type — the endpoint's @Consumes does not match the body's inferred type."
        )
    }

    override val name = NAME

    override val description = """
        |Perform a PUT request to the TeamCity REST API.
        |
        |IMPORTANT: Read "teamcity://guides/rest-api" before using this tool — see the PUT section.
        |TeamCity REST commonly uses PUT to replace a scalar field (build comment, pin flag, paused flag, parameter value). All `/app/rest/...` PUT paths are allowed — no allowlist.
        |Typical agent tasks: pause or unpause a build configuration, enable or disable an agent, update a build comment, pin a build, or replace tags/parameter values.
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
                    |  /app/rest/builds/id:123/comment
                    |  /app/rest/builds/id:123/pin/
                    |  /app/rest/buildTypes/id:BT1/paused
                    |  /app/rest/buildTypes/id:BT1/parameters/env.FOO
                """.trimMargin()
                )
            }
            putJsonObject("body") {
                put("type", "string")
                put(
                    "description", """
                    |Request body. Depending on the endpoint this is either:
                    | - a JSON object (e.g. {"status":true} for agents/enabledInfo), or
                    | - a plain scalar (e.g. "true"/"false" for paused/pin, or the value for parameters, or a comment text).
                    |Pass the raw body text — the tool does not wrap or transform it.
                """.trimMargin()
                )
            }
            putJsonObject("query") {
                put("type", "string")
                put("description", "Optional query parameters without leading '?'.")
            }
        },
        required = listOf("path", "body")
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

        val body = arguments["body"]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return McpToolResult.error("'body' parameter is required and must be non-empty")

        val client = restApiClient
            ?: return McpToolResult.error("REST API client is not configured")

        val query = arguments["query"]?.jsonPrimitive?.content?.trim()?.removePrefix("?") ?: ""

        return try {
            val response = client.put(normalizedPath, query, body)
            RestToolUtils.formatResponse(normalizedPath, query, response)
        } catch (e: RestApiException) {
            LOGGER.warnAndDebugDetails("REST PUT $normalizedPath failed with HTTP ${e.statusCode}", e)
            McpToolResult.error(RestToolUtils.formatRestApiError(e, ERROR_GUIDANCE))
        } catch (e: Exception) {
            LOGGER.warnAndDebugDetails("REST PUT $normalizedPath failed", e)
            McpToolResult.error("REST request failed: ${e.message}")
        }
    }
}
