package jetbrains.buildServer.ai.mcp.tools

import kotlinx.serialization.json.JsonObject

interface McpTool {
    val name: String
    val description: String
    val inputSchema: McpToolSchema
    suspend fun execute(arguments: JsonObject?): McpToolResult
}

/**
 * Marker for tools that have a brave/safe variant sharing the same [name].
 *
 * The configurator uses [brave] to keep only the variant matching the server's current brave-mode
 * setting: `brave = true` tools appear only when brave mode is on, `brave = false` tools
 * only when it is off. Tools that do not implement this interface are always included.
 */
interface BraveModeAwareMcpTool : McpTool {
    val brave: Boolean
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
