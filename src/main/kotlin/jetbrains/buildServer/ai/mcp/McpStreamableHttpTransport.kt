package jetbrains.buildServer.ai.mcp

import com.intellij.openapi.diagnostic.Logger
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * MCP Streamable HTTP transport implementing protocol version 2025-11-25 (JSON-only mode).
 *
 * All POST responses are returned as JSON bodies via [CompletableDeferred].
 * No SSE streams, no GET endpoint, no resumability.
 */
class McpStreamableHttpTransport(
    override val sessionId: String,
    executorService: ExecutorService,
    private val maxSessionDurationMs: Long = DEFAULT_MAX_SESSION_DURATION_MS,
    private val clientIdleTimeoutMs: Long = DEFAULT_CLIENT_IDLE_TIMEOUT_MS
) : AbstractTransport(), McpTransportSession {

    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }

    companion object {
        private val LOGGER = Logger.getInstance(McpStreamableHttpTransport::class.java.name)

        // Maximum session lifetime to prevent zombie connections (120 min default).
        // Coding sessions routinely last 1-2 hours, and most MCP clients (Claude Code,
        // Claude Desktop) do NOT auto-reconnect, so expiring too early forces manual intervention.
        const val DEFAULT_MAX_SESSION_DURATION_MS = 120 * 60 * 1000L

        // Client idle timeout - close if client doesn't send messages (30 min default).
        // Users frequently pause for 10-20+ min while thinking, reviewing, or writing code.
        // Since agents don't auto-reconnect on session expiry, we must be generous.
        const val DEFAULT_CLIENT_IDLE_TIMEOUT_MS = 30 * 60 * 1000L
    }

    private val open = AtomicBoolean(false)
    private val sessionStartTimeMs = System.currentTimeMillis()

    @Volatile
    private var lastClientActivityTimeMs = System.currentTimeMillis()

    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<JSONRPCMessage>>()

    private var _suspendOnClose: (suspend () -> Unit) = {}

    /**
     * Register a suspend callback to be invoked during [close].
     */
    fun suspendOnClose(block: suspend () -> Unit) {
        val old = _suspendOnClose
        _suspendOnClose = {
            old()
            block()
        }
    }

    private val coroutineScope = CoroutineScope(
        executorService.asCoroutineDispatcher() + CoroutineName("transport $sessionId") + SupervisorJob()
    )

    override suspend fun start() {
        if (open.compareAndSet(false, true)) {
            LOGGER.debug("Streamable HTTP transport started for session: $sessionId")
        } else {
            respondWithError("Transport already started")
        }
    }

    override fun isOpen(): Boolean = open.get()

    override suspend fun close() {
        if (open.compareAndSet(true, false)) {
            // Cancel all pending deferreds
            pendingResponses.values.forEach { deferred ->
                deferred.cancel(CancellationException("Transport closed"))
            }
            pendingResponses.clear()

            coroutineScope.cancel()
            try {
                _suspendOnClose.invoke()
            } catch (e: Throwable) {
                LOGGER.warn("Error invoking suspendOnClose for session $sessionId", e)
            }
            try {
                _onClose.invoke()
            } catch (e: Throwable) {
                LOGGER.warn("Error invoking onClose for session $sessionId", e)
            }
            LOGGER.debug("Transport closed for session: $sessionId")
        }
    }

    override suspend fun send(message: JSONRPCMessage) {
        send(message, null)
    }

    override suspend fun send(
        message: JSONRPCMessage,
        options: TransportSendOptions?
    ) {
        if (!open.get()) {
            respondWithError("Transport not established")
        }

        val responseId = when (message) {
            is JSONRPCResponse -> message.id
            is JSONRPCError -> message.id
            else -> null
        }

        if (responseId != null) {
            val key = requestIdKey(responseId)
            val deferred = pendingResponses.remove(key)
            if (deferred != null) {
                deferred.complete(message)
                LOGGER.debug("Completed deferred for request $key in session $sessionId")
            } else {
                LOGGER.debug("No pending deferred for request $key in session $sessionId, dropping message")
            }
        }
        // Messages without a response id (notifications, etc.) are dropped in JSON-only mode
    }

    /**
     * Register a [CompletableDeferred] for a request ID.
     * When [send] is called with a matching response, the deferred is completed.
     * Each deferred is one-shot: it is removed after completion.
     */
    fun captureResponse(requestId: RequestId): CompletableDeferred<JSONRPCMessage> {
        val key = requestIdKey(requestId)
        val deferred = CompletableDeferred<JSONRPCMessage>()
        val old = pendingResponses.put(key, deferred)
        if (old != null) {
            old.cancel(CancellationException("Replaced by new capture for $key"))
            LOGGER.warn("Replaced existing deferred for request $key in session $sessionId")
        }
        LOGGER.debug("Registered deferred for request $key in session $sessionId")
        return deferred
    }

    /**
     * Cancel and remove a pending response for a request ID.
     * Used when the HTTP request times out or is cancelled.
     */
    fun cancelResponse(requestId: RequestId, reason: String) {
        val key = requestIdKey(requestId)
        pendingResponses.remove(key)?.cancel(CancellationException(reason))
    }

    /**
     * Handle incoming POST message from client
     */
    suspend fun handlePostMessage(message: String) {
        if (open.get()) {
            handleMessage(message)
            lastClientActivityTimeMs = System.currentTimeMillis()
        } else {
            respondWithError("Transport not established")
        }
    }

    /**
     * Starts a session timeout watchdog that periodically checks for:
     * - Absolute session duration exceeded
     * - Client idle timeout exceeded
     *
     * When a timeout is detected, [onExpired] is called to trigger proper session termination
     * (remove from session manager, close transport, emit event). Falls back to [close] if
     * [onExpired] is not provided or throws.
     */
    fun initSessionTimeoutWatchdog(period: Duration, onExpired: ((reason: String) -> Unit)? = null) {
        coroutineScope.launch(CoroutineName("watchdog $sessionId")) {
            while (isActive) {
                if (!this@McpStreamableHttpTransport.open.get()) {
                    LOGGER.debug("Transport closed for session $sessionId, stopping watchdog")
                    break
                }

                val nowMs = System.currentTimeMillis()

                // Check: Absolute session timeout
                val sessionAgeMs = nowMs - sessionStartTimeMs
                if (sessionAgeMs > maxSessionDurationMs) {
                    LOGGER.info("Session $sessionId exceeded max duration (${sessionAgeMs}ms > ${maxSessionDurationMs}ms), closing")
                    expireSession(onExpired, "max session duration exceeded")
                    break
                }

                // Check: Client idle timeout
                val clientIdleTimeMs = nowMs - lastClientActivityTimeMs
                if (clientIdleTimeMs > clientIdleTimeoutMs) {
                    LOGGER.info("Client idle timeout for session $sessionId (${clientIdleTimeMs}ms > ${clientIdleTimeoutMs}ms), closing")
                    expireSession(onExpired, "client idle timeout exceeded")
                    break
                }

                delay(period)
            }
        }
    }

    private suspend fun expireSession(onExpired: ((String) -> Unit)?, reason: String) {
        if (onExpired != null) {
            try {
                onExpired(reason)
            } catch (e: Throwable) {
                LOGGER.warn("Session expiry callback failed for session $sessionId, falling back to direct close", e)
                close()
            }
        } else {
            close()
        }
    }

    private fun requestIdKey(id: RequestId): String = when (id) {
        is RequestId.StringId -> "req-${id.value}"
        is RequestId.NumberId -> "req-${id.value}"
    }

    private suspend fun handleMessage(message: String) {
        try {
            _onMessage.invoke(McpJson.decodeFromString<JSONRPCMessage>(message))
        } catch (e: Throwable) {
            try { _onError.invoke(e) } catch (errorCallbackEx: Throwable) {
                LOGGER.warn("Error callback failed for session $sessionId", errorCallbackEx)
            }
            throw e
        }
    }

    private fun respondWithError(error: String) {
        val e = ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, error)
        try { _onError.invoke(e) } catch (errorCallbackEx: Throwable) {
            LOGGER.warn("Error callback failed for session $sessionId", errorCallbackEx)
        }
        throw e
    }
}

// Copied from io.modelcontextprotocol.kotlin.sdk.shared to ensure serialization compatibility
@OptIn(ExperimentalSerializationApi::class)
internal val McpJson: Json by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        classDiscriminatorMode = ClassDiscriminatorMode.NONE
        explicitNulls = false
    }
}
