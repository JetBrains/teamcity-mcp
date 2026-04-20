package jetbrains.buildServer.ai.mcp.tools.rest

import jetbrains.buildServer.ai.mcp.tools.McpToolResult
import jetbrains.buildServer.version.ServerVersionHolder
import jetbrains.buildServer.version.ServerVersionInfo
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

    /**
     * displayVersion = 2025.11.2; return = 2025.11
     * displayVersion = 2026.1 EAP; return = 2026.1
     */
    private fun ServerVersionInfo.getYearPlusMajorDisplayVersion(): String {
        return "${displayVersionMajor}.${displayVersionMinor}"
    }

    fun restApiDocsUrl(): String {
        val version = ServerVersionHolder.getVersion().getYearPlusMajorDisplayVersion()
        return "https://www.jetbrains.com/help/teamcity/rest/$version/teamcity-rest-api-documentation.html"
    }

    /**
     * Sanitizes a query string for safe use in URLs.
     * Encodes `+` as `%2B` (otherwise interpreted as space) and spaces as `%20`.
     */
    fun sanitizeQuery(query: String): String =
        query.replace("+", "%2B").replace(" ", "%20")

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

    /**
     * Guidance for the HTTP status codes that every REST tool can encounter. Each tool merges
     * this with its own verb-specific entries (`400`, `409`, `415`, …) so the call site shows
     * exactly which codes that tool handles.
     *
     * Source of truth: the REST plugin's `errors` package (`XxxExceptionMapper.java`).
     *
     * Deliberately omitted:
     *  - 405 — callers validate path + HTTP method, so Method-Not-Allowed is unreachable.
     *  - 422 / 429 / 503 — the REST plugin does not produce these.
     *  - 406 / 416 — niche (bad Accept / range requests on file endpoints).
     */
    val COMMON_ERROR_GUIDANCE: Map<Int, String> = mapOf(
        401 to "Authentication required — the session's user context is missing or invalid.",
        403 to "Access denied. The current user lacks permission for this endpoint.",
        404 to "Resource not found. Check the path — the referenced entity may not exist.",
        500 to "TeamCity server error. Try again or simplify the request."
    )

    /**
     * Checks that [normalizedPath] is permitted for [method]. A `null` [allowedPaths] means
     * "no restriction"; an empty set means "block everything". Returns a ready-to-return error
     * result when blocked, or `null` when the path is allowed.
     */
    fun checkAllowedPath(
        method: String,
        normalizedPath: String,
        allowedPaths: Set<String>?
    ): McpToolResult? {
        if (allowedPaths == null || normalizedPath in allowedPaths) return null
        val list = if (allowedPaths.isEmpty()) "(none)" else allowedPaths.joinToString(", ")
        return McpToolResult.error("$method to '$normalizedPath' is not allowed. Allowed paths: $list")
    }

    /**
     * Assembles the standard JSON envelope for a REST response. Equivalent to
     * [buildResponseJson] with the URL pre-composed from [path] + optional [query].
     */
    fun formatResponse(
        path: String,
        query: String,
        response: RestApiResponse,
        notes: List<String> = emptyList()
    ): McpToolResult {
        val url = if (query.isNotEmpty()) "$path?$query" else path
        return buildResponseJson(url, response.statusCode, response.body, notes)
    }

    fun buildResponseJson(
        url: String,
        statusCode: Int,
        responseBody: String,
        notes: List<String> = emptyList(),
        isPlainText: Boolean = false,
        extraMeta: (JsonObjectBuilder.() -> Unit)? = null
    ): McpToolResult {
        val allNotes = notes.toMutableList()
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
                    allNotes.add("Response body was not valid JSON. Returned as plain text in bodyText.")
                }
            }
        }

        val payload = buildJsonObject {
            putJsonObject("meta") {
                put("url", url)
                put("statusCode", statusCode)
                extraMeta?.invoke(this)
                putJsonArray("notes") {
                    allNotes.forEach { add(it) }
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
