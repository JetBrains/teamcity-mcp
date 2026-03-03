package jetbrains.buildServer.ai.mcp.tools.rest

import com.intellij.openapi.diagnostic.Logger
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

interface RestApiClient {
    suspend fun get(path: String, query: String): RestApiResponse
    suspend fun post(path: String, query: String, body: String): RestApiResponse
}

data class RestApiResponse(
    val body: String,
    val statusCode: Int = 200,
    val truncated: Boolean = false
)

class RestApiException(
    val statusCode: Int,
    val statusText: String,
    override val message: String
) : Exception(message)

internal data class PagingResult(
    val query: String,
    val enforced: Boolean,
    val countCapped: Boolean,
    val note: String?
)

@Component
class RestGetTool(
    @Autowired(required = false) private val restApiClient: RestApiClient? = null
) : McpTool {

    companion object {
        private val LOGGER = Logger.getInstance(RestGetTool::class.java.name)

        internal const val NAME = "teamcity_rest_get"

        internal const val DEFAULT_PAGE_SIZE = 10
        internal const val MAX_PAGE_SIZE = 100

        private val LOCATOR_START_PATTERN = Regex("""(?:^|,)start:\d+""")
        private val LOCATOR_COUNT_PATTERN = Regex("""(?:^|,)count:(\d+)""")
        private val EMPTY_COUNT_PATTERN = Regex("""^\s*\{\s*"count"\s*:\s*0\s*[,}]""")

        private val ERROR_GUIDANCE = mapOf(
            400 to "Check your query syntax — locator or fields may be malformed.",
            403 to "Access denied. The current token may lack permission for this endpoint.",
            404 to "Resource not found. Check the path and locator — the resource may not exist or the ID may be wrong.",
            405 to "Method not allowed on this endpoint.",
            500 to "TeamCity server error. Try again or simplify the query."
        )
    }

    override val name = NAME

    override val description = """
        |Perform a GET request to the TeamCity REST API to retrieve server data.
        |
        |For JSON endpoints, always use 'fields' to select only the data you need - this is critical for keeping responses manageable.
        |Some endpoints return plain text (e.g. /builds/aggregated/.../status), where 'fields' is not applicable.
        |Always paginate with 'start' and 'count' (max $MAX_PAGE_SIZE). Use 'locator' to filter results server-side.
        |
        |Response format:
        |- Tool output is a JSON envelope with:
        |  meta(url,statusCode,truncated,hasNextHref,notes),
        |  contentType, and either body (JSON) or bodyText (plain text).
        |- Use meta.notes for guidance and warnings.
        |
        |Tips:
        |- Use fields=count to check total items before listing them.
        |- Use nextHref from responses to fetch the next page.
        |- If response is too large, use narrower fields or smaller count.
        |- If a request fails, adjust the query syntax and retry.
        |- Do not URL-encode parameters yourself - the server handles encoding.
        |- Use defaultFilter:true in locator to exclude canceled and personal builds.
    """.trimMargin()

    override val inputSchema = McpToolSchema(
        properties = buildJsonObject {
            putJsonObject("path") {
                put("type", "string")
                put(
                    "description", """
                    |Relative REST API path starting with /app/rest/.
                    |
                    |Common endpoints:
                    |  /app/rest/server - server info and version
                    |  /app/rest/builds - build history
                    |  /app/rest/builds/id:<buildId> - specific build details
                    |  /app/rest/builds/aggregated/<buildLocator>/status - aggregated build status (plain text)
                    |  /app/rest/buildQueue - queued builds
                    |  /app/rest/projects - all projects
                    |  /app/rest/buildTypes - build configurations
                    |  /app/rest/agents - build agents
                    |  /app/rest/changes - VCS changes
                    |  /app/rest/builds/id:<buildId>/testOccurrences - test results in a build
                    |  /app/rest/builds/id:<buildId>/problemOccurrences - build problems
                """.trimMargin()
                )
            }
            putJsonObject("query") {
                put("type", "string")
                put(
                    "description", """
                    |Query parameters without leading '?'. Combine locator, fields, and pagination with '&'.
                    |
                    |Field selection (critical for manageable response size):
                    |  fields=build(id,number,status) - nested field selection
                    |  fields=count - get only the total count
                    |  Some endpoints (e.g. /builds/aggregated/.../status) return plain text, so 'fields' is not used there
                    |
                    |Locator (server-side filtering):
                    |  locator=buildType:(id:MyBuild),status:SUCCESS,count:10
                    |
                    |Useful locator dimensions:
                    |  buildType:(id:BT1) - filter by build configuration
                    |  project:(id:P1) - filter by project
                    |  status:SUCCESS / status:FAILURE - filter by status
                    |  state:finished / state:running / state:queued - filter by state
                    |  branch:default:any - include all branches
                    |  defaultFilter:true - exclude personal and canceled builds (recommended)
                    |
                    |Pagination: include start and count as top-level params (start=0&count=10) or inside locator (locator=...,start:0,count:10). Max count is $MAX_PAGE_SIZE. If omitted, start=0 and count=$DEFAULT_PAGE_SIZE are added automatically.
                    |
                    |Examples:
                    |  locator=buildType:(id:MyBuild),defaultFilter:true,count:10&fields=build(id,number,status,finishOnAgentDate)
                    |  fields=project(id,name,parentProjectId)
                    |  locator=project:(id:MyProject)&fields=buildType(id,name)
                    |  fields=agent(id,name,connected,authorized)
                    |  locator=status:FAILURE&fields=testOccurrence(id,name,status,details)
                    |  locator=build:(id:12345)&fields=change(id,version,username,comment)
                """.trimMargin()
                )
            }
        },
        required = listOf("path")
    )

    override suspend fun execute(arguments: JsonObject?): McpToolResult {
        val path = arguments?.get("path")?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return McpToolResult.error("'path' parameter is required")

        RestToolUtils.validatePath(path)?.let { return it }

        val client = restApiClient
            ?: return McpToolResult.error("REST API client is not configured")

        val rawQuery = arguments["query"]?.jsonPrimitive?.content?.trim()?.removePrefix("?") ?: ""
        val paging = ensurePaging(rawQuery)
        val hasFields = hasFieldsParam(paging.query)

        return try {
            val response = client.get(path, paging.query)
            formatResponse(path, paging, response, hasFields)
        } catch (e: RestApiException) {
            LOGGER.warnAndDebugDetails("REST GET $path failed with HTTP ${e.statusCode}", e)
            McpToolResult.error(RestToolUtils.formatRestApiError(e, ERROR_GUIDANCE))
        } catch (e: Exception) {
            LOGGER.warnAndDebugDetails("REST GET $path failed", e)
            McpToolResult.error("REST request failed: ${e.message}")
        }
    }

    internal fun ensurePaging(query: String): PagingResult {
        if (query.isBlank()) {
            return PagingResult(
                query = "start=0&count=$DEFAULT_PAGE_SIZE",
                enforced = true,
                countCapped = false,
                note = "Pagination was automatically added: start=0, count=$DEFAULT_PAGE_SIZE. There may be more results."
            )
        }

        val params = parseQueryParams(query)
        val locatorValue = params.firstOrNull { it.first == "locator" }?.second ?: ""

        val hasStart = params.any { it.first == "start" } ||
            LOCATOR_START_PATTERN.containsMatchIn(locatorValue)
        val hasCount = params.any { it.first == "count" } ||
            LOCATOR_COUNT_PATTERN.containsMatchIn(locatorValue)

        // Cap count if it exceeds MAX_PAGE_SIZE
        val capResult = capCount(query, params, locatorValue)

        if (hasStart && hasCount) {
            return if (capResult != null) {
                PagingResult(capResult.query, enforced = false, countCapped = true, note = capResult.note)
            } else {
                PagingResult(query, enforced = false, countCapped = false, note = null)
            }
        }

        val workingQuery = capResult?.query ?: query
        val additions = mutableListOf<String>()
        if (!hasStart) additions.add("start=0")
        if (!hasCount) additions.add("count=$DEFAULT_PAGE_SIZE")

        val newQuery = workingQuery + "&" + additions.joinToString("&")
        val addedDesc = additions.joinToString(", ")

        val notes = mutableListOf<String>()
        notes.add("Pagination parameters were automatically added: $addedDesc. There may be more results.")
        if (capResult?.note != null) notes.add(capResult.note)

        return PagingResult(
            query = newQuery,
            enforced = true,
            countCapped = capResult != null,
            note = notes.joinToString("\n")
        )
    }

    private fun capCount(
        query: String,
        params: List<Pair<String, String>>,
        locatorValue: String
    ): CapResult? {
        // Check top-level count
        val topLevelCount = params.firstOrNull { it.first == "count" }
        if (topLevelCount != null) {
            val countVal = topLevelCount.second.toIntOrNull()
            if (countVal != null && countVal > MAX_PAGE_SIZE) {
                val newQuery = query.replaceFirst(
                    "count=${topLevelCount.second}",
                    "count=$MAX_PAGE_SIZE"
                )
                return CapResult(newQuery, "Count was reduced from $countVal to $MAX_PAGE_SIZE (maximum allowed).")
            }
        }

        // Check locator-embedded count
        val locatorMatch = LOCATOR_COUNT_PATTERN.find(locatorValue)
        if (locatorMatch != null) {
            val countVal = locatorMatch.groupValues[1].toIntOrNull()
            if (countVal != null && countVal > MAX_PAGE_SIZE) {
                val oldFragment = "count:$countVal"
                val newFragment = "count:$MAX_PAGE_SIZE"
                val newQuery = query.replaceFirst(oldFragment, newFragment)
                return CapResult(newQuery, "Count was reduced from $countVal to $MAX_PAGE_SIZE (maximum allowed).")
            }
        }

        return null
    }

    private fun formatResponse(
        path: String,
        paging: PagingResult,
        response: RestApiResponse,
        hasFields: Boolean
    ): McpToolResult {
        val url = if (paging.query.isNotEmpty()) "$path?${paging.query}" else path
        val isPlainTextEndpoint = isPlainTextEndpoint(path)
        val notes = mutableListOf<String>()

        paging.note?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { notes.add(it) }

        if (!hasFields && !isPlainTextEndpoint) {
            notes.add("No 'fields' parameter specified. Response may be large. Consider adding fields to select only needed data, e.g. fields=build(id,number,status)")
        }

        if (response.truncated) {
            notes.add("Response was truncated due to size limits. Use narrower 'fields' or smaller 'count'.")
        }

        val hasNextHref = !isPlainTextEndpoint && response.body.contains("\"nextHref\"")
        if (hasNextHref) {
            notes.add("More results available. Use the 'nextHref' value from the response to fetch the next page.")
        }

        if (!isPlainTextEndpoint && EMPTY_COUNT_PATTERN.containsMatchIn(response.body)) {
            notes.add("Result contains 0 items. Verify your locator filters or try a broader query.")
        }

        return RestToolUtils.buildResponseJson(
            url = url,
            statusCode = response.statusCode,
            responseBody = response.body,
            notes = notes,
            isPlainText = isPlainTextEndpoint
        ) {
            put("truncated", response.truncated)
            put("hasNextHref", hasNextHref)
        }
    }

    private fun hasFieldsParam(query: String): Boolean {
        return parseQueryParams(query).any { it.first == "fields" }
    }

    private fun parseQueryParams(query: String): List<Pair<String, String>> {
        return query.split("&")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { p ->
                val idx = p.indexOf('=')
                if (idx > 0) Pair(p.take(idx), p.substring(idx + 1))
                else Pair(p, "")
            }
    }

    private fun isPlainTextEndpoint(path: String): Boolean {
        val normalized = path.trimEnd('/')
        return normalized.contains("/aggregated/") && normalized.endsWith("/status")
    }
}

private data class CapResult(val query: String, val note: String)
