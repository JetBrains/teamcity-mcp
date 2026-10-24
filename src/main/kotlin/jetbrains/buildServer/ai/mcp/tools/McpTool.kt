package jetbrains.buildServer.ai.mcp.tools

import kotlinx.serialization.json.JsonObject

interface McpTool {
    val name: String
    val description: String
    val inputSchema: McpToolSchema
    val annotations: McpToolAnnotations
    suspend fun execute(arguments: JsonObject?): McpToolResult
}

/**
 * MCP tool annotations (spec `ToolAnnotations`), plus the human-readable [title].
 *
 * The spec treats every hint as optional and defaults an absent one to the worst case
 * (`readOnly=false`, `destructive=true`, `openWorld=true`). Store reviews (OpenAI, the Anthropic
 * directory) require them to be explicit, which is why the hints here are non-null and have no defaults.
 *
 * See https://modelcontextprotocol.io/specification/2025-11-25/schema#toolannotations
 */
data class McpToolAnnotations(
    val title: String,
    /** The tool does not modify its environment. */
    val readOnly: Boolean,
    /** The tool may delete, overwrite or cancel something. Meaningful only when [readOnly] is false. */
    val destructive: Boolean,
    /** Repeated calls with the same arguments have no additional effect. */
    val idempotent: Boolean,
    /** The tool reaches entities outside the TeamCity server it runs in. */
    val openWorld: Boolean
) {
    companion object {
        /** The spec's worst case for every hint. For tools not exposed in production. */
        fun worstCase(title: String) = McpToolAnnotations(
            title = title,
            readOnly = false,
            destructive = true,
            idempotent = false,
            openWorld = true
        )
    }
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
