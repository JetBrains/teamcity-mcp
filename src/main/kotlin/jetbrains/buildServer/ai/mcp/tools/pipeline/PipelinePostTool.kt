package jetbrains.buildServer.ai.mcp.tools.pipeline

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.ai.mcp.SettingsService
import jetbrains.buildServer.ai.mcp.tools.McpTool
import jetbrains.buildServer.ai.mcp.tools.McpToolResult
import jetbrains.buildServer.ai.mcp.tools.McpToolSchema
import jetbrains.buildServer.ai.mcp.tools.rest.RestApiException
import jetbrains.buildServer.ai.mcp.tools.rest.RestToolUtils
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class PipelinePostTool(
    @Autowired(required = false) private val pipelineApiClient: PipelineApiClient? = null,
    private val settingsService: SettingsService
) : McpTool {

    companion object {
        private val LOGGER = Logger.getInstance(PipelinePostTool::class.java.name)
        internal const val NAME = "teamcity_pipeline_post"

        private val ERROR_GUIDANCE = mapOf(
            400 to "Check the request body and query parameters for this pipeline endpoint.",
            403 to "Access denied. The current token may lack permission for pipeline endpoints.",
            404 to "Resource not found. Check the path or pipeline id.",
            405 to "Method not allowed on this pipeline endpoint.",
            409 to "Conflict — pipeline state changed or the request conflicts with current data.",
            500 to "TeamCity server error. Try again or simplify the request."
        )
    }

    override val name = NAME

    override val description = """
        |Perform a POST request to TeamCity Pipelines controller endpoints under /app/pipeline.
        |
        |IMPORTANT: Read "teamcity://guides/pipelines" before using this tool.
        |This tool is independent from teamcity_rest_post.
        |
        |Allowed POST paths come from the `teamcity.ai.mcp.pipeline.post.allowed.paths` property. If that property is absent, all `/app/pipeline...` POST paths are enabled.
        |Note: some read-like pipeline helpers (compatibility checks, schema generation) are POST endpoints too, so they go through this tool.
        |
        |Common endpoints:
        |- path=/app/pipeline, query=parentProjectExtId=...
        |- /app/pipeline/<pipelineId>
        |- path=/app/pipeline/schema/generate, query=pipelineId=...
        |- /app/pipeline/repository/testConnection
        |- /app/pipeline/repository/checkVersionedSettings
        |- /app/pipeline/<pipelineId>/parameters/resolve
        |- /app/pipeline/<pipelineId>/buildStepDescription
        |- /app/pipeline/<pipelineId>/job-descriptions
        |- /app/pipeline/repository/branches
        |- /app/pipeline/<pipelineId>/compatibility/agents
        |- /app/pipeline/<pipelineId>/compatibility/agents/<jobId>
        |- /app/pipeline/<pipelineId>/kotlinDsl
        |
        |Body guidance:
        |- Most pipeline POST endpoints expect a full draft-pipeline JSON object, not a tiny patch.
        |- Parameter resolution is the main exception: it expects {"pipeline": <draft>, "scope": {...}}.
        |
        |Response format:
        |- Same JSON envelope as the REST tools: meta(url,statusCode,notes), contentType, body/bodyText.
    """.trimMargin()

    override val inputSchema = McpToolSchema(
        properties = buildJsonObject {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Pipeline endpoint path starting with /app/pipeline")
            }
            putJsonObject("query") {
                put("type", "string")
                put("description", "Optional query parameters without leading '?', e.g. parentProjectExtId=MyProject or pipelineId=MyPipeline")
            }
            putJsonObject("body") {
                put("type", "string")
                put("description", "JSON request body. Must be a JSON object.")
            }
        },
        required = listOf("path", "body")
    )

    override suspend fun execute(arguments: JsonObject?): McpToolResult {
        val rawPath = arguments?.get("path")?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return McpToolResult.error("'path' parameter is required")

        PipelineToolUtils.validatePath(rawPath)?.let { return it }
        val path = PipelineToolUtils.normalizePath(rawPath)

        val allowedPaths = settingsService.getPipelinePostAllowedPaths()
        if (allowedPaths != null && !PipelineToolUtils.matchesAllowedPath(path, allowedPaths)) {
            return McpToolResult.error(
                "POST to '$path' is not allowed. Allowed pipeline POST paths: ${allowedPaths.joinToString(", ")}"
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

        val client = pipelineApiClient
            ?: return McpToolResult.error("Pipeline API client is not configured")

        val query = arguments["query"]?.jsonPrimitive?.content?.trim()?.removePrefix("?") ?: ""

        return try {
            val response = client.post(path, query, parsedBody.toString())
            PipelineToolUtils.buildResponse(path, query, response)
        } catch (e: RestApiException) {
            LOGGER.warn("Pipeline POST $path failed with HTTP ${e.statusCode}", e)
            McpToolResult.error(PipelineToolUtils.formatError(e, ERROR_GUIDANCE))
        } catch (e: Exception) {
            LOGGER.warn("Pipeline POST $path failed", e)
            McpToolResult.error("Pipeline request failed: ${e.message}")
        }
    }
}
