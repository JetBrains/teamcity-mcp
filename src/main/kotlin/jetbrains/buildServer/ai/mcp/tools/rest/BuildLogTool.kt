package jetbrains.buildServer.ai.mcp.tools.rest

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.ai.mcp.tools.McpTool
import jetbrains.buildServer.ai.mcp.tools.McpToolResult
import jetbrains.buildServer.ai.mcp.tools.McpToolSchema
import jetbrains.buildServer.serverSide.TeamCityProperties
import jetbrains.buildServer.serverSide.BuildsManager
import jetbrains.buildServer.serverSide.buildLog.BuildLogReaderEx
import jetbrains.buildServer.serverSide.buildLog.LogMessage
import jetbrains.buildServer.serverSide.buildLog.LogView
import kotlinx.serialization.json.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class BuildLogTool(
    @Autowired(required = false) private val buildsManager: BuildsManager? = null,
    private val maxScanLimitOverride: Int? = null
) : McpTool {

    companion object {
        private val LOGGER = Logger.getInstance(BuildLogTool::class.java.name)

        internal const val NAME = "teamcity_build_log"
        internal const val DEFAULT_COUNT = 100
        internal const val MAX_COUNT = 300
        internal const val MAX_SCAN = 50_000
        internal const val MAX_SCAN_PROPERTY = "teamcity.ai.mcp.buildLog.filtering.maxMessagesToScan"
        internal const val MAX_RESPONSE_SIZE = 300_000  // ~300 KB max response to protect LLM context

        internal fun statusPrefix(priority: Int): String? = when (priority) {
            4 -> "[ERROR] "
            3 -> "[FAILURE] "
            2 -> "[WARNING] "
            else -> null
        }

        /**
         * Minimum status priority for client-side filtering. null means no filtering.
         * Status priorities: UNKNOWN(0), NORMAL(1), WARNING(2), FAILURE(3), ERROR(4).
         */
        private fun statusFilterThreshold(filter: String?): Int? = when (filter?.lowercase()) {
            "errors" -> 3    // FAILURE (3) and ERROR (4)
            "warnings" -> 2  // WARNING (2), FAILURE (3), ERROR (4)
            else -> null      // no client-side filtering
        }

        private fun stringParam(arguments: JsonObject?, key: String): String? = try {
            arguments?.get(key)?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }

    }

    override val name = NAME

    override val description = """
        |View build log messages with filtering and pagination.
        |
        |Returns build log as plain text in original log message order.
        |Individual log messages may span multiple lines.
        |Lines with warnings/errors are prefixed: [WARNING], [FAILURE], [ERROR].
        |Normal messages have no prefix.
        |
        |Pagination: header shows next page start when more messages are available, or "end of log" when the log is fully read.
        |
        |Tips:
        |- Use filter=errors to quickly find build failures and errors.
        |- Use start and count for pagination through large logs.
        |- Combine with teamcity_rest_get for build metadata and test results.
    """.trimMargin()

    override val inputSchema = McpToolSchema(
        properties = buildJsonObject {
            putJsonObject("buildId") {
                put("type", "string")
                put("description", "The build ID (numeric) to retrieve the log for. Example: \"12345\"")
            }
            putJsonObject("filter") {
                put("type", "string")
                put(
                    "description", """
                    |Message status filter:
                    |  (omitted or all) - show all messages (default)
                    |  errors - show only FAILURE and ERROR messages
                    |  warnings - show WARNING, FAILURE, and ERROR messages
                """.trimMargin()
                )
            }
            putJsonObject("start") {
                put("type", "string")
                put("description", "Starting message index for pagination (default: 0)")
            }
            putJsonObject("count") {
                put("type", "string")
                put("description", "Number of messages to retrieve (default: $DEFAULT_COUNT, max: $MAX_COUNT)")
            }
        },
        required = listOf("buildId")
    )

    override suspend fun execute(arguments: JsonObject?): McpToolResult {
        val buildIdStr = stringParam(arguments, "buildId")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return McpToolResult.error("'buildId' parameter is required")

        val buildId = buildIdStr.toLongOrNull()
            ?: return McpToolResult.error("'buildId' must be a numeric value")

        val manager = buildsManager
            ?: return McpToolResult.error("Build log service is not available")

        val filter = stringParam(arguments, "filter")?.trim()
            ?.takeIf { it.isNotBlank() }
        val start = stringParam(arguments, "start")?.trim()
            ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val requestedCount = stringParam(arguments, "count")?.trim()
            ?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_COUNT
        val count = requestedCount.coerceAtMost(MAX_COUNT)

        val statusThreshold = statusFilterThreshold(filter)

        val notes = mutableListOf<String>()
        if (requestedCount > MAX_COUNT) {
            notes.add("Count was reduced from $requestedCount to $MAX_COUNT (maximum allowed).")
        }

        return try {
            val build = manager.findBuildInstanceById(buildId)
                ?: return McpToolResult.error("Build $buildId not found")

            val buildLog = build.getBuildLog()
            if (buildLog !is BuildLogReaderEx) {
                return McpToolResult.error("Build log is not available for build $buildId")
            }

            val iterator = buildLog.createIterator(start, LogView.LINEAR)
            readAndFormat(count, iterator, statusThreshold, notes)
        } catch (e: Exception) {
            LOGGER.warnAndDebugDetails("Build log request for build $buildId failed", e)
            McpToolResult.error("Build log request failed: ${e.message}")
        }
    }

    private fun readAndFormat(
        count: Int,
        rawIterator: Iterator<LogMessage>,
        statusThreshold: Int?,
        notes: MutableList<String>
    ): McpToolResult {
        val lines = mutableListOf<String>()
        var lastScannedIndex = -1
        var scanned = 0
        var hasMore = false
        var totalSize = 0
        var sizeLimitReached = false
        var nextPageStartOverride: Int? = null
        val maxScan = if (statusThreshold != null) (maxScanLimitOverride ?: getBuildLogMaxScan()) else Int.MAX_VALUE

        while (rawIterator.hasNext()) {
            if (lines.size >= count) {
                hasMore = true
                break
            }
            if (scanned >= maxScan) {
                hasMore = rawIterator.hasNext()
                break
            }

            val msg = rawIterator.next()
            lastScannedIndex = msg.index
            scanned++

            val statusPriority = msg.status.priority.toInt()

            // Apply client-side status filter
            if (statusThreshold != null && statusPriority < statusThreshold) continue

            val prefix = statusPrefix(statusPriority) ?: ""
            val line = "$prefix${msg.text}"

            // Check size limit (always allow at least one message)
            if (lines.isNotEmpty() && totalSize + line.length > MAX_RESPONSE_SIZE) {
                nextPageStartOverride = msg.index  // re-fetch this message on next page
                hasMore = true
                sizeLimitReached = true
                break
            }

            lines.add(line)
            totalSize += line.length
        }

        val lastMessageIncluded = !hasMore

        if (statusThreshold != null) {
            val statusLabel = if (statusThreshold >= 3) "FAILURE/ERROR" else "WARNING/FAILURE/ERROR"
            notes.add("Status filter applied: showing $statusLabel messages ($scanned scanned, ${lines.size} matched).")
            if (scanned >= maxScan && lines.size < count) {
                notes.add("Scan limit reached ($maxScan messages). Use a higher 'start' to continue.")
            }
        }

        if (sizeLimitReached) {
            notes.add("Response truncated: data size limit reached. Use next page start to continue.")
        }

        if (!lastMessageIncluded) {
            notes.add("More messages available. Use next page start to fetch the next page.")
        }

        // Build output: single header line + notes + log lines
        val nextPageStart = nextPageStartOverride ?: (lastScannedIndex + 1)
        val tail = if (lastMessageIncluded) "end of log" else "next page start: $nextPageStart"
        val sb = StringBuilder()
        sb.appendLine("--- Build log: ${lines.size} messages, $tail ---")
        for (note in notes) {
            sb.appendLine("# $note")
        }
        sb.appendLine()
        for (line in lines) {
            sb.appendLine(line)
        }

        return McpToolResult.success(sb.toString())
    }
}

private fun getBuildLogMaxScan(): Int =
    TeamCityProperties.getInteger(BuildLogTool.MAX_SCAN_PROPERTY, BuildLogTool.MAX_SCAN).coerceAtLeast(1)
