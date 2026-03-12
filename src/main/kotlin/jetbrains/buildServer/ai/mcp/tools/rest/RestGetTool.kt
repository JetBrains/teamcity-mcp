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
        |IMPORTANT: Before your first REST API call, read the resource "teamcity://guides/rest-api" for comprehensive guidance on endpoints, locators, field selection, and pagination.
        |For investigating build failures, also read "teamcity://guides/build-failure-analysis" for a step-by-step methodology.
        |
        |For JSON endpoints, always use 'fields' to select only the data you need - this is critical for keeping responses manageable.
        |Some endpoints return plain text (e.g. /builds/aggregated/.../status), where 'fields' is not applicable.
        |Always paginate with 'start' and 'count' inside the locator (max $MAX_PAGE_SIZE). Do NOT use start/count as top-level query parameters — they are deprecated.
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
                    |  /app/rest/changes - VCS changes (use locator=build:(id:<buildId>) to filter by build)
                    |  /app/rest/testOccurrences - test results (use locator=build:(id:<buildId>) to filter by build)
                    |  /app/rest/problemOccurrences - build problems (use locator=build:(id:<buildId>) to filter by build)
                    |
                    |  IMPORTANT: Do NOT use sub-resource paths like /builds/id:<buildId>/testOccurrences or
                    |  /builds/id:<buildId>/problemOccurrences for querying lists — they return ALL items ignoring
                    |  pagination. Always use top-level endpoints with locator filters instead.
                """.trimMargin()
                )
            }
            putJsonObject("query") {
                put("type", "string")
                put(
                    "description", """
                    |Query parameters without leading '?'. Combine locator and fields with '&'.
                    |
                    |Field selection (critical for manageable response size):
                    |  fields=build(id,number,status) - nested field selection
                    |  fields=count - get only the total count
                    |  Some endpoints (e.g. /builds/aggregated/.../status) return plain text, so 'fields' is not used there
                    |
                    |Locator (server-side filtering and pagination):
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
                    |Pagination: always use start and count inside the locator (e.g. locator=...,start:0,count:10). Do NOT use start/count as top-level query parameters — they are deprecated and will be automatically migrated into the locator. Max count is $MAX_PAGE_SIZE. If omitted, start:0 and count:$DEFAULT_PAGE_SIZE are added to the locator automatically.
                    |
                    |Examples:
                    |  locator=buildType:(id:MyBuild),defaultFilter:true,count:10&fields=build(id,number,status,finishOnAgentDate)
                    |  fields=project(id,name,parentProjectId)
                    |  locator=project:(id:MyProject)&fields=buildType(id,name)
                    |  fields=agent(id,name,connected,authorized)
                    |  locator=build:(id:12345),status:FAILURE&fields=testOccurrence(id,name,status,details)
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
                query = "locator=start:0,count:$DEFAULT_PAGE_SIZE",
                enforced = true,
                countCapped = false,
                note = "Pagination was automatically added: start:0, count:$DEFAULT_PAGE_SIZE. There may be more results."
            )
        }

        val params = parseQueryParams(query)
        val locatorValue = params.firstOrNull { it.first == "locator" }?.second ?: ""

        // Detect pagination in locator
        val hasLocatorStart = LOCATOR_START_PATTERN.containsMatchIn(locatorValue)
        val hasLocatorCount = LOCATOR_COUNT_PATTERN.containsMatchIn(locatorValue)

        // Detect deprecated top-level start/count
        val rawTopStart = params.firstOrNull { it.first == "start" }
        val rawTopCount = params.firstOrNull { it.first == "count" }

        // Only migrate into locator if the locator doesn't already have them
        val topStartToMigrate = if (!hasLocatorStart) rawTopStart else null
        val topCountToMigrate = if (!hasLocatorCount) rawTopCount else null

        // Migrate top-level start/count into locator
        val migrated = migrateTopLevelPaging(params, locatorValue, topStartToMigrate, topCountToMigrate)

        val hasStart = hasLocatorStart || rawTopStart != null
        val hasCount = hasLocatorCount || rawTopCount != null

        // Strip any remaining deprecated top-level start/count even if not migrated
        val workingQuery = if (migrated != null) {
            migrated.query
        } else if (rawTopStart != null || rawTopCount != null) {
            // Locator already has them — just strip the top-level duplicates
            val remaining = params.filter { it.first != "start" && it.first != "count" }
            remaining.joinToString("&") { (k, v) -> if (v.isEmpty()) k else "$k=$v" }
        } else {
            query
        }
        val workingParams = if (migrated != null) parseQueryParams(workingQuery) else params
        val workingLocator = workingParams.firstOrNull { it.first == "locator" }?.second ?: ""

        // Cap count in locator if it exceeds MAX_PAGE_SIZE
        val capResult = capLocatorCount(workingQuery, workingLocator)

        if (hasStart && hasCount) {
            val notes = mutableListOf<String>()
            if (migrated != null) notes.add(migrated.note)
            if (capResult != null) notes.add(capResult.note)
            val finalQuery = capResult?.query ?: workingQuery
            return PagingResult(
                finalQuery,
                enforced = migrated != null,
                countCapped = capResult != null,
                note = notes.joinToString("\n").ifEmpty { null }
            )
        }

        // Add missing pagination to locator
        val queryAfterCap = capResult?.query ?: workingQuery
        val paramsAfterCap = parseQueryParams(queryAfterCap)
        val locatorAfterCap = paramsAfterCap.firstOrNull { it.first == "locator" }?.second ?: ""

        val additions = mutableListOf<String>()
        if (!hasStart) additions.add("start:0")
        if (!hasCount) additions.add("count:$DEFAULT_PAGE_SIZE")
        val addedDims = additions.joinToString(",")

        val newQuery = if (locatorAfterCap.isNotEmpty()) {
            // Append to existing locator
            val newLocator = "$locatorAfterCap,$addedDims"
            rebuildQueryWithLocator(paramsAfterCap, newLocator)
        } else {
            // Create new locator param
            val otherParams = paramsAfterCap.filter { it.first != "locator" }
            val parts = mutableListOf("locator=$addedDims")
            otherParams.forEach { (k, v) -> parts.add(if (v.isEmpty()) k else "$k=$v") }
            parts.joinToString("&")
        }

        val notes = mutableListOf<String>()
        notes.add("Pagination parameters were automatically added: $addedDims. There may be more results.")
        if (migrated != null) notes.add(migrated.note)
        if (capResult?.note != null) notes.add(capResult.note)

        return PagingResult(
            query = newQuery,
            enforced = true,
            countCapped = capResult != null,
            note = notes.joinToString("\n")
        )
    }

    /**
     * Migrates deprecated top-level start= and count= query parameters into the locator.
     * Returns null if no migration was needed.
     */
    private fun migrateTopLevelPaging(
        params: List<Pair<String, String>>,
        locatorValue: String,
        topStart: Pair<String, String>?,
        topCount: Pair<String, String>?
    ): MigrationResult? {
        if (topStart == null && topCount == null) return null

        val dimsToAdd = mutableListOf<String>()
        if (topStart != null) dimsToAdd.add("start:${topStart.second}")
        if (topCount != null) dimsToAdd.add("count:${topCount.second}")

        val newLocator = if (locatorValue.isNotEmpty()) {
            "$locatorValue,${dimsToAdd.joinToString(",")}"
        } else {
            dimsToAdd.joinToString(",")
        }

        val remainingParams = params.filter { it.first != "start" && it.first != "count" && it.first != "locator" }
        val parts = mutableListOf("locator=$newLocator")
        remainingParams.forEach { (k, v) -> parts.add(if (v.isEmpty()) k else "$k=$v") }
        val newQuery = parts.joinToString("&")

        val migratedDesc = dimsToAdd.joinToString(", ")
        return MigrationResult(newQuery, "Migrated deprecated top-level parameters into locator: $migratedDesc.")
    }

    private fun capLocatorCount(query: String, locatorValue: String): CapResult? {
        val locatorMatch = LOCATOR_COUNT_PATTERN.find(locatorValue) ?: return null
        val countVal = locatorMatch.groupValues[1].toIntOrNull() ?: return null
        if (countVal <= MAX_PAGE_SIZE) return null

        val oldFragment = "count:$countVal"
        val newFragment = "count:$MAX_PAGE_SIZE"
        val newQuery = query.replaceFirst(oldFragment, newFragment)
        return CapResult(newQuery, "Count was reduced from $countVal to $MAX_PAGE_SIZE (maximum allowed).")
    }

    private fun rebuildQueryWithLocator(
        params: List<Pair<String, String>>,
        newLocator: String
    ): String {
        val parts = mutableListOf<String>()
        for ((k, v) in params) {
            if (k == "locator") {
                parts.add("locator=$newLocator")
            } else {
                parts.add(if (v.isEmpty()) k else "$k=$v")
            }
        }
        return parts.joinToString("&")
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
private data class MigrationResult(val query: String, val note: String)
