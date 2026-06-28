package jetbrains.buildServer.ai.mcp.tools.pipeline

import jetbrains.buildServer.ai.mcp.tools.McpToolResult
import jetbrains.buildServer.ai.mcp.tools.rest.RestApiException
import jetbrains.buildServer.ai.mcp.tools.rest.RestApiResponse
import jetbrains.buildServer.ai.mcp.tools.rest.RestToolUtils
import jetbrains.buildServer.ai.mcp.tools.rest.RestToolUtils.REST_PATH_PREFIX
import org.springframework.util.AntPathMatcher

internal object PipelineToolUtils {
    const val PIPELINE_PATH_PREFIX = "/app/pipeline"
    private val pathMatcher = AntPathMatcher()

    fun normalizePath(path: String): String {
        return if (path == PIPELINE_PATH_PREFIX) path else path.trimEnd('/')
    }

    fun validatePath(path: String, queryParamName: String = "query"): McpToolResult? {
        if (!(path == PIPELINE_PATH_PREFIX || path.startsWith("$PIPELINE_PATH_PREFIX/"))) {
            val errorMessage = buildString {
                append("Path must start with $PIPELINE_PATH_PREFIX")
                if (path.startsWith(REST_PATH_PREFIX)) {
                    append(". Use rest tools for $REST_PATH_PREFIX calls.")
                }
            }
            return McpToolResult.error(errorMessage)
        }
        if (path.contains("..")) {
            return McpToolResult.error("Path must not contain '..' segments")
        }
        if (path.contains("?")) {
            return McpToolResult.error("Path must not contain query parameters. Use the '$queryParamName' parameter instead.")
        }
        if (path.contains("#")) {
            return McpToolResult.error("Path must not contain '#' fragment identifiers")
        }
        return null
    }

    fun matchesAllowedPath(path: String, allowedPatterns: Set<String>): Boolean {
        val normalizedPath = normalizePath(path)
        return allowedPatterns.any { pattern ->
            val normalizedPattern = if (pattern == PIPELINE_PATH_PREFIX) pattern else pattern.trimEnd('/')
            when {
                normalizedPattern == normalizedPath -> true
                normalizedPattern.contains("*") || normalizedPattern.contains("?") ->
                    pathMatcher.match(normalizedPattern, normalizedPath)
                else -> false
            }
        }
    }

    fun formatError(e: RestApiException, guidance: Map<Int, String>): String =
        RestToolUtils.formatRestApiError(e, guidance)

    fun buildResponse(
        path: String,
        query: String,
        response: RestApiResponse,
        notes: MutableList<String> = mutableListOf()
    ): McpToolResult {
        val url = if (query.isNotEmpty()) "$path?$query" else path
        return RestToolUtils.buildResponseJson(
            url = url,
            statusCode = response.statusCode,
            responseBody = response.body,
            notes = notes
        )
    }
}
