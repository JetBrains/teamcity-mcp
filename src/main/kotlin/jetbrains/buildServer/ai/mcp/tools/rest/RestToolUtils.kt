package jetbrains.buildServer.ai.mcp.tools.rest

import jetbrains.buildServer.ai.mcp.tools.McpToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal object RestToolUtils {
    const val REST_PATH_PREFIX = "/app/rest/"
    val json: Json = Json

    fun validatePath(path: String, queryParamName: String = "query"): McpToolResult? {
        if (!path.startsWith(REST_PATH_PREFIX)) {
            return McpToolResult.error("Path must start with $REST_PATH_PREFIX")
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

    fun parseJsonOrNull(text: String): JsonElement? {
        return try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            null
        }
    }

    fun formatRestApiError(e: RestApiException, guidance: Map<Int, String>): String {
        val msg = guidance[e.statusCode]
            ?: (if (e.statusCode in 500..599) guidance[500] else null)
            ?: ""
        val base = "HTTP ${e.statusCode} ${e.statusText}: ${e.message}"
        return if (msg.isNotEmpty()) "$base\n$msg" else base
    }

    fun buildResponseJson(
        url: String,
        statusCode: Int,
        responseBody: String,
        notes: MutableList<String>,
        isPlainText: Boolean = false,
        extraMeta: (JsonObjectBuilder.() -> Unit)? = null
    ): McpToolResult {
        var contentType = "text/plain"
        var body: JsonElement? = null
        var bodyText: String? = null

        if (isPlainText) {
            bodyText = responseBody
        } else {
            val parsed = parseJsonOrNull(responseBody)
            if (parsed != null) {
                contentType = "application/json"
                body = parsed
            } else {
                bodyText = responseBody
                if (responseBody.isNotBlank()) {
                    notes.add("Response body was not valid JSON. Returned as plain text in bodyText.")
                }
            }
        }

        val payload = buildJsonObject {
            putJsonObject("meta") {
                put("url", url)
                put("statusCode", statusCode)
                extraMeta?.invoke(this)
                putJsonArray("notes") {
                    notes.forEach { add(it) }
                }
            }
            put("contentType", contentType)
            if (body != null) {
                put("body", body)
            } else {
                put("bodyText", bodyText ?: "")
            }
        }

        return McpToolResult.success(payload.toString())
    }
}
