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
class PipelineDeleteTool(
    @Autowired(required = false) private val pipelineApiClient: PipelineApiClient? = null
) : McpTool {

    companion object {
        private val LOGGER = Logger.getInstance(PipelineDeleteTool::class.java.name)
        internal const val NAME = "teamcity_pipeline_delete"

        private val ERROR_GUIDANCE = mapOf(
            403 to "Access denied. The current token may lack permission to delete this pipeline.",
            404 to "Pipeline not found. Check the pipeline id.",
            500 to "TeamCity server error. The pipeline may have running builds."
        )
    }

    override val name = NAME

    override val description = """
        |Delete a TeamCity Pipeline by id.
        |
        |IMPORTANT: Read "teamcity://guides/pipelines" before using this tool.
        |This is a destructive operation — it removes the pipeline and its backing project permanently.
        |
        |Safety model:
        |- Pipeline support must be enabled.
        |- Brave mode must be enabled.
        |
        |Usage:
        |- path=/app/pipeline/<pipelineId>
        |
        |Response format:
        |- Same JSON envelope as the REST tools: meta(url,statusCode,notes), contentType, body/bodyText.
        |- Successful deletion returns status 200 with an empty body.
    """.trimMargin()

    override val inputSchema = McpToolSchema(
        properties = buildJsonObject {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Pipeline endpoint path: /app/pipeline/<pipelineId>")
            }
        },
        required = listOf("path")
    )

    override suspend fun execute(arguments: JsonObject?): McpToolResult {
        val rawPath = arguments?.get("path")?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return McpToolResult.error("'path' parameter is required")

        PipelineToolUtils.validatePath(rawPath)?.let { return it }
        val path = PipelineToolUtils.normalizePath(rawPath)

        if (path == PipelineToolUtils.PIPELINE_PATH_PREFIX || path == "${PipelineToolUtils.PIPELINE_PATH_PREFIX}/") {
            return McpToolResult.error("Cannot delete without a pipeline id. Use /app/pipeline/<pipelineId>")
        }

        // Only allow direct pipeline paths, not sub-resources
        val pipelineId = path.removePrefix("${PipelineToolUtils.PIPELINE_PATH_PREFIX}/")
        if (pipelineId.contains("/")) {
            return McpToolResult.error("DELETE only supports /app/pipeline/<pipelineId>, not sub-resource paths")
        }

        val client = pipelineApiClient
            ?: return McpToolResult.error("Pipeline API client is not configured")

        return try {
            val response = client.delete(path, "")
            PipelineToolUtils.buildResponse(path, "", response)
        } catch (e: RestApiException) {
            LOGGER.warn("Pipeline DELETE $path failed with HTTP ${e.statusCode}", e)
            McpToolResult.error(PipelineToolUtils.formatError(e, ERROR_GUIDANCE))
        } catch (e: Exception) {
            LOGGER.warn("Pipeline DELETE $path failed", e)
            McpToolResult.error("Pipeline delete failed: ${e.message}")
        }
    }
}
