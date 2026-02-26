package jetbrains.buildServer.ai.mcp.tools

import kotlinx.serialization.json.JsonObject

interface McpTool {
    val name: String
    val description: String
    val inputSchema: McpToolSchema
    suspend fun execute(arguments: JsonObject?): McpToolResult
}

data class McpToolSchema(
    val properties: JsonObject? = null,
    val required: List<String>? = null
)

data class McpToolResult(
    val text: String,
    val isError: Boolean = false
) {
    companion object {
        fun success(text: String) = McpToolResult(text)
        fun error(text: String) = McpToolResult(text, isError = true)
    }
}
