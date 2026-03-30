package jetbrains.buildServer.ai.mcp.events

const val MCP_FUS_ENABLED = "teamcity.ai.mcp.fus.enabled"

/**
 * Listener for MCP lifecycle events.
 */
fun interface McpEventHandler {
    fun onEvent(event: McpEvent)
}
