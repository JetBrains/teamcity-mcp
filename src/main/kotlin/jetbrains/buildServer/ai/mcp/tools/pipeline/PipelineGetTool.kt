package jetbrains.buildServer.ai.mcp.tools.pipeline

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.ai.mcp.tools.McpTool
import jetbrains.buildServer.ai.mcp.tools.McpToolResult
import jetbrains.buildServer.ai.mcp.tools.McpToolSchema
import jetbrains.buildServer.ai.mcp.tools.rest.RestApiException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class PipelineGetTool(
    @Autowired(required = false) private val pipelineApiClient: PipelineApiClient? = null
) : McpTool {

    companion object {
        private val LOGGER = Logger.getInstance(PipelineGetTool::class.java.name)
        internal const val NAME = "teamcity_pipeline_get"

        private val ERROR_GUIDANCE = mapOf(
            400 to "Check the path and query parameters for this pipeline endpoint.",
            403 to "Access denied. The current token may lack permission for pipeline endpoints.",
            404 to "Resource not found. Check the pipeline id/path or query parameters.",
            405 to "Method not allowed on this pipeline endpoint.",
            500 to "TeamCity server error. Try again or simplify the request."
        )
    }

    override val name = NAME

    override val description = """
        |Perform a GET request to TeamCity Pipelines controller endpoints under /app/pipeline.
        |
        |IMPORTANT: Read "teamcity://guides/pipelines" before using this tool.
        |This tool is independent from teamcity_rest_get and targets the non-REST pipeline controllers under /app/pipeline.
        |
        |Use it for endpoints such as:
        |- path=/app/pipeline, query=parentProjectExtId=...
        |- /app/pipeline/<pipelineId>
        |- /app/pipeline/<pipelineId>/parameters
        |- path=/app/pipeline/<pipelineId>/optimizations, query=branch=...
        |- /app/pipeline/<pipelineId>/<runId>/optimizations
        |- /app/pipeline/<pipelineId>/<runId>/vcsRoots
        |- path=/app/pipeline/provider/vcs, query=parentProjectExtId=...
        |- path=/app/pipeline/provider/vcs/<connectionId>/repositories, query=parentProjectExtId=...&q=...
        |- /app/pipeline/provider/...
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
                put("description", "Query parameters without leading '?', e.g. parentProjectExtId=MyProject or branch=main")
            }
        },
        required = listOf("path")
    )

    override suspend fun execute(arguments: JsonObject?): McpToolResult {
        val rawPath = arguments?.get("path")?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return McpToolResult.error("'path' parameter is required")

        PipelineToolUtils.validatePath(rawPath)?.let { return it }

        val client = pipelineApiClient
            ?: return McpToolResult.error("Pipeline API client is not configured")

        val path = PipelineToolUtils.normalizePath(rawPath)
        val query = arguments["query"]?.jsonPrimitive?.content?.trim()?.removePrefix("?") ?: ""

        return try {
            val response = client.get(path, query)
            PipelineToolUtils.buildResponse(path, query, response)
        } catch (e: RestApiException) {
            LOGGER.warn("Pipeline GET $path failed with HTTP ${e.statusCode}", e)
            McpToolResult.error(PipelineToolUtils.formatError(e, ERROR_GUIDANCE))
        } catch (e: Exception) {
            LOGGER.warn("Pipeline GET $path failed", e)
            McpToolResult.error("Pipeline request failed: ${e.message}")
        }
    }
}
