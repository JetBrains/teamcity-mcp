package jetbrains.buildServer.ai.mcp.events

import java.time.Instant

sealed class McpEvent {
    abstract val timestamp: Instant

    /** A new client session has been initialised and the MCP server is connected. */
    data class SessionStarted(
        val sessionId: String,
        val clientInfo: String?,
        override val timestamp: Instant = Instant.now()
    ) : McpEvent()

    /** A session has been closed (transport removed or client-initiated DELETE). */
    data class SessionClosed(
        val sessionId: String,
        val reason: String,
        override val timestamp: Instant = Instant.now()
    ) : McpEvent()

    /** A JSON-RPC message was received from the client on an existing session. */
    data class MessageReceived(
        val sessionId: String,
        /** The JSON-RPC method name, or null for responses/notifications without a method field. */
        val method: String?,
        override val timestamp: Instant = Instant.now()
    ) : McpEvent()

//    /** An error occurred during session or message handling. */
//    data class ErrorOccurred(
//        val sessionId: String?,
//        val error: Throwable,
//        val context: String,
//        override val timestamp: Instant = Instant.now()
//    ) : McpEvent()
}
