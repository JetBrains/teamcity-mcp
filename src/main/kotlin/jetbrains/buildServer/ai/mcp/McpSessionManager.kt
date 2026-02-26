package jetbrains.buildServer.ai.mcp

import com.intellij.openapi.diagnostic.Logger
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for MCP transport sessions.
 */
interface McpTransportSession {
    /**
     * Unique session id
     */
    val sessionId: String

    /**
     * Send a JSON-RPC message to the client
     */
    suspend fun send(message: JSONRPCMessage)

    /**
     * Close the transport session
     */
    suspend fun close()

    /**
     * Check if the transport is still open
     */
    fun isOpen(): Boolean
}

/**
 * Manager for MCP transport sessions.
 */
@Service
class McpSessionManager {

    companion object {
        private val LOGGER = Logger.getInstance(McpSessionManager::class.java.name)
    }

    private val sessions = ConcurrentHashMap<String, McpTransportSession>()

    /**
     * Register a new transport session.
     * If a session with the same ID already exists and is still open, it will be closed.
     */
    suspend fun registerSession(session: McpTransportSession) {
        val oldSession = sessions.put(session.sessionId, session)
        if (oldSession != null && oldSession.isOpen()) {
            LOGGER.warn("Replacing open session ${session.sessionId}; closing old session to prevent resource leak")
            try {
                oldSession.close()
            } catch (e: Throwable) {
                LOGGER.warn("Error closing old session ${session.sessionId}", e)
            }
        }
        LOGGER.info("Registered session: ${session.sessionId}")
    }

    fun getSession(sessionId: String): McpTransportSession? {
        return sessions[sessionId]
    }

    fun removeSession(sessionId: String): McpTransportSession? {
        return sessions.remove(sessionId)?.also {
            LOGGER.info("Removed session: $sessionId")
        }
    }

    fun getAllSessionIds(): Set<String> {
        return sessions.keys.toSet()
    }

    fun getSessionCount(): Int {
        return sessions.size
    }

    suspend fun closeAll() {
        LOGGER.info("Closing all ${sessions.size} active sessions")
        val sessionIds = sessions.keys.toList()
        for (id in sessionIds) {
            val session = sessions.remove(id) ?: continue
            try {
                session.close()
            } catch (e: Throwable) {
                LOGGER.warn("Error closing session ${session.sessionId}", e)
            }
        }
    }

    fun cleanupClosedSessions(): Int {
        var count = 0
        val iter = sessions.entries.iterator()
        while (iter.hasNext()) {
            val (sessionId, session) = iter.next()
            if (!session.isOpen()) {
                iter.remove()
                LOGGER.debug("Cleaned up closed session: $sessionId")
                count++
            }
        }
        return count
    }
}
