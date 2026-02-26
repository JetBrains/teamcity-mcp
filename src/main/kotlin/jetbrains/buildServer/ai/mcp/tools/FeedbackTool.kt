package jetbrains.buildServer.ai.mcp.tools

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.springframework.stereotype.Component

@Component
class FeedbackTool : McpTool {

    companion object {
        private val LOGGER = Logger.getInstance(FeedbackTool::class.java.name)

        internal const val NAME = "feedback"
        internal val ALLOWED_CATEGORIES = setOf("bug", "feature_request", "usability", "tool_quality", "missing_capability")
    }

    override val name = NAME

    override val description = """
        Report feedback about this MCP server to help TeamCity developers improve the AI agent experience.

        Use this to share:
        - Tool gaps: missing capabilities needed to complete tasks
        - Tool quality: unclear descriptions, unhelpful responses, missing parameters
        - Usability issues: confusing errors, unexpected behavior, workflow friction
        - Bugs: incorrect results, failures, inconsistencies

        Be specific: include what you tried, what happened, and what you expected.
    """.trimIndent()

    override val inputSchema = McpToolSchema(
        properties = buildJsonObject {
            putJsonObject("category") {
                put("type", "string")
                putJsonArray("enum") {
                    ALLOWED_CATEGORIES.forEach { add(JsonPrimitive(it)) }
                }
                put("description", "The type of feedback")
            }
            putJsonObject("message") {
                put("type", "string")
                put("description", "What you tried, what happened, what you expected or need")
            }
            putJsonObject("tool_name") {
                put("type", "string")
                put("description", "Name of the specific tool this feedback is about, if applicable")
            }
        },
        required = listOf("category", "message")
    )

    override suspend fun execute(arguments: JsonObject?): McpToolResult {
        val category = arguments?.get("category")?.jsonPrimitive?.content
        if (category.isNullOrBlank()) {
            return McpToolResult.error("'category' parameter is required")
        }
        if (category !in ALLOWED_CATEGORIES) {
            return McpToolResult.error("Invalid category '$category'. Allowed values: ${ALLOWED_CATEGORIES.joinToString()}")
        }

        val message = arguments["message"]?.jsonPrimitive?.content
        if (message.isNullOrBlank()) {
            return McpToolResult.error("'message' parameter is required")
        }

        val toolName = arguments["tool_name"]?.jsonPrimitive?.content
        val logMessage = buildString {
            append("MCP feedback [$category]")
            if (toolName != null) append(" tool=$toolName")
            append(": $message")
        }

        LOGGER.info(logMessage)

        val summary = if (message.length > 100) message.take(100) + "..." else message
        return McpToolResult.success("Feedback recorded [$category]: $summary")
    }
}
