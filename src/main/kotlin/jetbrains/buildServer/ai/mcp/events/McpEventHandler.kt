package jetbrains.buildServer.ai.mcp.events

/**
 * Listener for MCP lifecycle events.
 */
fun interface McpEventHandler {
    fun onEvent(event: McpEvent)
}
