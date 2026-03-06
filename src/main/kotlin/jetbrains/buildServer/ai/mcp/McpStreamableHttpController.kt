package jetbrains.buildServer.ai.mcp

import com.intellij.openapi.diagnostic.Logger
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import jetbrains.buildServer.ai.mcp.events.McpEvent
import jetbrains.buildServer.ai.mcp.events.McpEventBus
import jetbrains.buildServer.ai.mcp.tools.rest.impl.McpToolExecutionContext
import jetbrains.buildServer.serverSide.TeamCityProperties
import jetbrains.buildServer.util.NamedDaemonThreadFactory
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.SerializationException
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.beans.factory.DisposableBean
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val MCP_PATH = "/mcp"

private const val PROTOCOL_VERSION_HEADER = McpProtocolVersion.HEADER_NAME
private const val SESSION_ID_HEADER = "Mcp-Session-Id"
private const val CONTENT_TYPE_HEADER = "Content-Type"
private const val ACCEPT_HEADER = "Accept"

private const val APPLICATION_JSON = "application/json"

/**
 * MCP Streamable HTTP controller implementing protocol version 2025-11-25 (JSON-only mode).
 *
 * All POST responses are returned as `application/json` bodies.
 * GET returns 405 Method Not Allowed (no SSE streams).
 *
 * Endpoint: /mcp
 * - POST initialize: returns JSON body with InitializeResult + Mcp-Session-Id header
 * - POST request: returns JSON body with response
 * - POST notification: returns 202 Accepted
 * - GET: 405 Method Not Allowed
 * - DELETE: Terminate session
 */
@RestController
@RequestMapping(MCP_PATH)
class McpStreamableHttpController(
    private val settingsService: SettingsService,
    private val sessionManager: McpSessionManager,
    private val serverConfigurator: McpServerConfigurator,
    private val eventBus: McpEventBus,
    private val toolExecutionContext: McpToolExecutionContext
) : DisposableBean, CoroutineScope {

    companion object {
        private val LOGGER = Logger.getInstance(McpStreamableHttpController::class.java.name)
    }

    // TODO: add executor metrics
    private val coroutineExecutor = Executors.newScheduledThreadPool(
        getThreadPoolSize(15),
        NamedDaemonThreadFactory("mcp")
    )

    private val job = SupervisorJob()

    override val coroutineContext: CoroutineContext =
        coroutineExecutor.asCoroutineDispatcher() + job + CoroutineName("controller")

    /**
     * POST endpoint: Handle JSON-RPC messages.
     *
     * Returns `DeferredResult<ResponseEntity<String>>` for all message types:
     * - Initialize: JSON body with InitializeResult + Mcp-Session-Id header
     * - Request: JSON body with response
     * - Notification: 202 Accepted
     */
    @PostMapping
    fun handlePost(
        @RequestHeader(PROTOCOL_VERSION_HEADER, required = false) protocolVersion: String?,
        @RequestHeader(SESSION_ID_HEADER, required = false) sessionId: String?,
        @RequestHeader(ACCEPT_HEADER, required = false) accept: String?,
        @RequestHeader(CONTENT_TYPE_HEADER) contentType: String,
        @RequestBody body: String,
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse
    ): DeferredResult<ResponseEntity<String>> {
        if (!settingsService.isMcpServerEnabled()) {
            throwUnavailable()
        }

        logIncomingRequest(LOGGER, "POST", servletRequest, body)

        val contentTypeNormalized = contentType.substringBefore(";").trim().lowercase()
        if (contentTypeNormalized != APPLICATION_JSON) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "$CONTENT_TYPE_HEADER must be $APPLICATION_JSON, got: $contentType"
            )
        }

        val messageJson = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: Throwable) {
            LOGGER.warn("Failed to parse JSON-RPC message for sessionId: $sessionId", e)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON-RPC message", e)
        }

        val isInitialize = isInitializeRequest(messageJson)

        if (isInitialize) {
            val clientInfo = extractClientInfo(messageJson)
            LOGGER.info("Initialize request received: protocolVersion=$protocolVersion, clientInfo=$clientInfo")
            eventBus.emit(McpEvent.InitializeRequested(protocolVersion, clientInfo))
        }

        validateProtocolVersion(protocolVersion)
        validateAcceptHeader(accept)

        if (isInitialize && sessionId != null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Initialize request must not include $SESSION_ID_HEADER header"
            )
        }

        if (!isInitialize && sessionId == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "$SESSION_ID_HEADER header required for non-initialize requests"
            )
        }

        return if (isInitialize && sessionId == null) {
            handleInitializePost(body, messageJson, servletRequest, servletResponse)
        } else if (sessionId != null) {
            val isRequest = isRequest(messageJson)
            if (isRequest) {
                handleRequestPost(sessionId, body, messageJson, servletRequest, servletResponse)
            } else {
                handleNotificationPost(sessionId, body, messageJson, servletRequest, servletResponse)
            }
        } else {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "$SESSION_ID_HEADER header required for non-initialize requests"
            )
        }
    }

    private fun <T> launchDeferred(
        coroutineName: String,
        logContext: String,
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse,
        capturedSecurityContext: SecurityContext = SecurityContextHolder.getContext(),
        block: suspend (DeferredResult<ResponseEntity<T>>) -> Unit
    ): DeferredResult<ResponseEntity<T>> {
        val result = DeferredResult<ResponseEntity<T>>(getRequestTimeoutMs())
        val job = AtomicReference<Job?>(null)
        result.onTimeout {
            LOGGER.warn("$logContext: timed out")
            result.setErrorResult(
                ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "Request timed out")
            )
            job.get()?.cancel(CancellationException("$logContext: timed out"))
        }
        job.set(launch(CoroutineName(coroutineName)) {
            try {
                // Here we basically propagate SecurityContext and original servletRequest/servletResponse to the coroutine to be used by tools
                toolExecutionContext.withOperationContext(
                    servletRequest = servletRequest,
                    servletResponse = servletResponse,
                    capturedSecurityContext = capturedSecurityContext
                ) {
                    block(result)
                }
            } catch (_: CancellationException) {
                LOGGER.debug("$logContext: cancelled")
                result.setErrorResult(
                    ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Request cancelled")
                )
            } catch (e: ResponseStatusException) {
                result.setErrorResult(e)
            } catch (e: Throwable) {
                LOGGER.warn("$logContext: error", e)
                result.setErrorResult(classifyError(e))
            }
        })
        return result
    }

    private fun classifyError(e: Throwable): ResponseStatusException {
        return if (isInvalidJsonRpc(e)) {
            ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON-RPC message", e)
        } else {
            ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", e)
        }
    }

    /**
     * Initialize flow: returns DeferredResult with JSON body containing InitializeResult.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun handleInitializePost(
        body: String,
        messageJson: JsonObject,
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse
    ): DeferredResult<ResponseEntity<String>> {
        val requestId = extractRequestId(messageJson)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing or invalid 'id' in initialize request")
        val clientInfo = extractClientInfo(messageJson)

        return launchDeferred(
            coroutineName = "POST init",
            logContext = "POST initialize requestId=${requestId.displayValue()}",
            servletRequest = servletRequest,
            servletResponse = servletResponse
        ) { result ->
            val newSessionId = Uuid.random().toString()
            LOGGER.info("Initializing new MCP session: $newSessionId (clientInfo: $clientInfo)")

            val transport = createSession(newSessionId)

            val responseDeferred = transport.captureResponse(requestId)
            try {
                transport.handlePostMessage(body)

                val response = responseDeferred.await()
                val responseJson = McpJson.encodeToString(response)

                result.setResult(
                    ResponseEntity.ok()
                        .header(SESSION_ID_HEADER, newSessionId)
                        .header(CONTENT_TYPE_HEADER, APPLICATION_JSON)
                        .body(responseJson)
                )
            } catch (e: Throwable) {
                val reason = if (e is CancellationException) "Initialize request cancelled" else "Initialize request failed"
                transport.cancelResponse(requestId, reason)
                withContext(NonCancellable) { cleanupFailedCreateSession(newSessionId, transport, reason) }
                throw e
            }
        }
    }

    /**
     * Request flow: returns DeferredResult with JSON body containing the response.
     * Uses [McpStreamableHttpTransport.captureResponse] to await the SDK response.
     */
    private fun handleRequestPost(
        sessionId: String,
        body: String,
        messageJson: JsonObject,
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse
    ): DeferredResult<ResponseEntity<String>> {
        val requestId = extractRequestId(messageJson) ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Missing or invalid 'id' in request"
        )

        return launchDeferred(
            coroutineName = "POST request $sessionId/${requestId.displayValue()}",
            logContext = "POST request sessionId=$sessionId requestId=${requestId.displayValue()}",
            servletRequest = servletRequest,
            servletResponse = servletResponse
        ) { result ->
            val session = getOrCreateSession(sessionId)
            val responseDeferred = session.captureResponse(requestId)
            try {
                session.handlePostMessage(body)
                eventBus.emit(McpEvent.MessageReceived(sessionId, extractMethod(messageJson)))

                val response = responseDeferred.await()
                val responseJson = McpJson.encodeToString(response)
                result.setResult(
                    ResponseEntity.ok()
                        .header(CONTENT_TYPE_HEADER, APPLICATION_JSON)
                        .body(responseJson)
                )
            } catch (e: Throwable) {
                val reason = if (e is CancellationException) "HTTP request cancelled" else "HTTP request failed"
                session.cancelResponse(requestId, reason)
                throw e
            }
        }
    }

    /**
     * Notification flow: returns DeferredResult with 202 Accepted.
     */
    private fun handleNotificationPost(
        sessionId: String,
        body: String,
        messageJson: JsonObject,
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse
    ): DeferredResult<ResponseEntity<String>> {
        return launchDeferred(
            coroutineName = "POST notif $sessionId",
            logContext = "POST notification sessionId=$sessionId",
            servletRequest = servletRequest,
            servletResponse = servletResponse
        ) { result ->
            val session = getOrCreateSession(sessionId)
            session.handlePostMessage(body)
            eventBus.emit(McpEvent.MessageReceived(sessionId, extractMethod(messageJson)))
            result.setResult(ResponseEntity.accepted().build())
        }
    }

    /**
     * GET endpoint: returns 405 Method Not Allowed.
     * JSON-only mode does not support SSE streams.
     *
     * TODO: Some MCP clients (e.g. certain agent frameworks) attempt GET for server-initiated
     *  SSE streams. Track these requests to evaluate whether to implement this endpoint.
     */
    @GetMapping
    fun handleGet(
        @RequestHeader(PROTOCOL_VERSION_HEADER, required = false) protocolVersion: String?,
        @RequestHeader(SESSION_ID_HEADER, required = false) sessionId: String?,
        @RequestHeader("User-Agent", required = false) userAgent: String?,
        servletRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        if (!settingsService.isMcpServerEnabled()) {
            throwUnavailable()
        }
        logIncomingRequest(LOGGER, "GET", servletRequest)
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build()
    }

    /**
     * DELETE endpoint: Terminate session
     */
    @DeleteMapping
    fun handleDelete(
        @RequestHeader(SESSION_ID_HEADER, required = false) sessionId: String?,
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse
    ): DeferredResult<ResponseEntity<Void>> {
        if (!settingsService.isMcpServerEnabled()) {
            throwUnavailable()
        }

        logIncomingRequest(LOGGER, "DELETE", servletRequest)

        if (sessionId == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "$SESSION_ID_HEADER header required for DELETE requests"
            )
        }

        return launchDeferred(
            coroutineName = "DELETE $sessionId",
            logContext = "DELETE sessionId=$sessionId",
            servletRequest = servletRequest,
            servletResponse = servletResponse
        ) { result ->
            try {
                when (terminateSession(sessionId, "client request")) {
                    SessionTerminationResult.CLOSED -> {
                        LOGGER.info("Session terminated: $sessionId")
                        result.setResult(ResponseEntity.ok().build())
                    }
                    SessionTerminationResult.CLOSE_TIMED_OUT ->
                        throw ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Session close timed out but session was removed"
                        )
                    SessionTerminationResult.CLOSE_FAILED ->
                        throw ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Session close failed but session was removed"
                        )
                    SessionTerminationResult.NOT_FOUND -> {
                        LOGGER.warn("Session not found: $sessionId")
                        throw ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Session $sessionId not found"
                        )
                    }
                }
            } catch (e: ResponseStatusException) {
                throw e
            } catch (e: Throwable) {
                val reason = if (e is CancellationException) "DELETE request cancelled" else "DELETE request error"
                withContext(NonCancellable) { terminateSession(sessionId, reason) }
                throw e
            }
        }
    }

    private fun validateProtocolVersion(version: String?) {
        val validationResult = McpProtocolVersion.validate(version)
        when (validationResult) {
            is McpProtocolVersion.ValidationResult.Valid -> {
                LOGGER.debug("Protocol version validated: ${validationResult.version}")
            }
            is McpProtocolVersion.ValidationResult.Missing -> {
                LOGGER.debug("Protocol version missing, using default: ${validationResult.defaultVersion}")
            }
            is McpProtocolVersion.ValidationResult.Invalid -> {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    validationResult.reason
                )
            }
        }
    }

    private fun validateAcceptHeader(accept: String?) {
        if (accept == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Accept header must include $APPLICATION_JSON"
            )
        }
        val acceptLower = accept.lowercase()
        if (!acceptLower.contains(APPLICATION_JSON)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Accept header must include $APPLICATION_JSON"
            )
        }
    }

    private fun isInitializeRequest(messageJson: JsonObject): Boolean {
        val hasId = messageJson.containsKey("id")
        val hasMethod = messageJson.containsKey("method")
        val method = try {
            messageJson["method"]?.jsonPrimitive?.content
        } catch (_: Throwable) {
            null
        }
        return hasId && hasMethod && method == "initialize"
    }

    private fun isRequest(messageJson: JsonObject): Boolean {
        val hasId = messageJson.containsKey("id")
        val hasMethod = messageJson.containsKey("method")
        return hasId && hasMethod
    }

    private fun extractMethod(messageJson: JsonObject): String? = try {
        messageJson["method"]?.jsonPrimitive?.content
    } catch (_: Throwable) {
        null
    }

    private fun extractClientInfo(messageJson: JsonObject): String? = try {
        messageJson["params"]?.jsonObject?.get("clientInfo")?.toString()
    } catch (_: Exception) {
        null
    }

    private fun RequestId.displayValue(): String = when (this) {
        is RequestId.StringId -> value
        is RequestId.NumberId -> value.toString()
    }

    private fun extractRequestId(messageJson: JsonObject): RequestId? {
        val idElement = messageJson["id"] ?: return null
        if (idElement !is kotlinx.serialization.json.JsonPrimitive) return null
        return if (idElement.isString) {
            RequestId(idElement.content)
        } else {
            val longVal = idElement.content.toLongOrNull() ?: return null
            RequestId(longVal)
        }
    }

    /**
     * Get an existing session or create a new one if not found locally.
     * Supports multinode scenarios where session can be initialized on another node.
     */
    private suspend fun getOrCreateSession(sessionId: String): McpStreamableHttpTransport {
        val existing = sessionManager.getSession(sessionId)
        if (existing != null) {
            if (existing !is McpStreamableHttpTransport) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Session $sessionId is not a streamable HTTP transport"
                )
            }
            return existing
        }

        LOGGER.info("Session $sessionId not found locally, creating new session")
        return createSession(sessionId)
    }

    /**
     * Create a new MCP session with the given ID: transport + server + watchdog.
     * Used by both initialize flow and session recovery.
     */
    private suspend fun createSession(sessionId: String): McpStreamableHttpTransport {
        val transport = McpStreamableHttpTransport(
            sessionId = sessionId,
            executorService = coroutineExecutor,
            maxSessionDurationMs = getMaxSessionDurationMs(),
            clientIdleTimeoutMs = getClientIdleTimeoutMs()
        )
        try {
            serverConfigurator.configureServer().let { server ->
                server.onClose {
                    LOGGER.info("Server connection closed for session: $sessionId")
                    terminateSessionAsync(sessionId, "server closed")
                }
                server.createSession(transport)
                LOGGER.debug("Server connected to transport for session: $sessionId")
            }

            sessionManager.registerSession(transport)
            eventBus.emit(McpEvent.SessionStarted(sessionId, null))

            transport.initSessionTimeoutWatchdog(getWatchdogPeriodSec().seconds) { reason ->
                terminateSessionAsync(sessionId, reason)
            }

            return transport
        } catch (e: Throwable) {
            withContext(NonCancellable) { cleanupFailedCreateSession(sessionId, transport, "session creation failed") }
            throw e
        }
    }

    private fun isInvalidJsonRpc(e: Throwable): Boolean {
        return e is SerializationException || e is IllegalArgumentException
    }

    private fun terminateSessionAsync(sessionId: String, reason: String) {
        launch(CoroutineName("terminate-session $sessionId")) {
            try {
                when (terminateSession(sessionId, reason)) {
                    SessionTerminationResult.CLOSED -> {
                        LOGGER.info("Session terminated: $sessionId, reason: $reason")
                    }
                    SessionTerminationResult.CLOSE_TIMED_OUT -> {
                        LOGGER.warn("Session termination timed out for sessionId: $sessionId, reason: $reason. Session was removed.")
                    }
                    SessionTerminationResult.CLOSE_FAILED -> {
                        LOGGER.warn("Session termination failed for sessionId: $sessionId, reason: $reason. Session was removed.")
                    }
                    SessionTerminationResult.NOT_FOUND -> {
                        LOGGER.debug("Session already removed for session: $sessionId, reason: $reason")
                    }
                }
            } catch (_: CancellationException) {
                LOGGER.debug("Transport removal cancelled for sessionId: $sessionId, attempting force-terminate")
                withContext(NonCancellable) {
                    terminateSession(sessionId, "$reason (remove cancelled)")
                }
            } catch (e: Throwable) {
                LOGGER.warn("Error removing transport for sessionId: $sessionId, attempting force-terminate", e)
                withContext(NonCancellable) {
                    terminateSession(sessionId, "$reason (remove error)")
                }
            }
        }
    }

    private suspend fun cleanupFailedCreateSession(sessionId: String, transport: McpStreamableHttpTransport?, reason: String) {
        sessionManager.removeSession(sessionId)
        if (transport != null) {
            closeTransportSafely(
                transport,
                timeoutMs = getCloseTimeoutMs(),
                sessionId = sessionId,
                reason = reason
            )
        }
    }

    private suspend fun terminateSession(sessionId: String, reason: String): SessionTerminationResult {
        val transport = sessionManager.removeSession(sessionId)
            ?: return SessionTerminationResult.NOT_FOUND

        val closeResult = closeTransportSafely(
            transport,
            timeoutMs = getCloseTimeoutMs(),
            sessionId = sessionId,
            reason = reason
        )

        eventBus.emit(McpEvent.SessionClosed(sessionId, reason))
        return closeResult
    }

    private suspend fun closeTransportSafely(
        transport: McpTransportSession,
        timeoutMs: Long,
        sessionId: String,
        reason: String
    ): SessionTerminationResult = withContext(NonCancellable) {
        try {
            withTimeout(timeoutMs) {
                transport.close()
            }
            SessionTerminationResult.CLOSED
        } catch (_: TimeoutCancellationException) {
            LOGGER.warn("Timed out closing transport for sessionId: $sessionId, reason: $reason")
            SessionTerminationResult.CLOSE_TIMED_OUT
        } catch (e: Throwable) {
            LOGGER.warn("Failed to close transport for sessionId: $sessionId, reason: $reason", e)
            SessionTerminationResult.CLOSE_FAILED
        }
    }

    override fun destroy() {
        job.cancel()

        runBlocking {
            withTimeoutOrNull(getDestroyTimeoutMs()) {
                sessionManager.closeAll()
            } ?: LOGGER.warn("Session cleanup timed out during destroy")
        }

        coroutineExecutor.shutdown()
        try {
            if (!coroutineExecutor.awaitTermination(getDestroyTimeoutMs(), TimeUnit.MILLISECONDS)) {
                LOGGER.warn("Executor did not terminate in time, forcing shutdown")
                coroutineExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            LOGGER.warn("Interrupted while waiting for executor termination", e)
            coroutineExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun throwUnavailable(): Nothing {
        throw ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MCP Streamable HTTP is disabled. Use '${MCP_FEATURE_TOGGLE}' internal property to enable it."
        )
    }
}

private enum class SessionTerminationResult {
    CLOSED,
    CLOSE_TIMED_OUT,
    CLOSE_FAILED,
    NOT_FOUND
}

private fun getWatchdogPeriodSec(): Int =
    TeamCityProperties
        .getInteger("teamcity.ai.mcp.watchdog.periodSec", 30)
        .coerceAtLeast(1)

private fun getCloseTimeoutMs(): Long =
    TeamCityProperties
        .getInteger("teamcity.ai.mcp.close.timeoutSec", 5)
        .coerceAtLeast(1) * 1000L

private fun getRequestTimeoutMs(): Long {
    val rawRequestTimeoutMs = TeamCityProperties
        .getInteger("teamcity.ai.mcp.request.timeoutSec", 60)
        .coerceAtLeast(1) * 1000L
    return max(rawRequestTimeoutMs, getCloseTimeoutMs() + 1000L)
}

private fun getDestroyTimeoutMs(): Long {
    val rawDestroyTimeoutMs = TeamCityProperties
        .getInteger("teamcity.ai.mcp.destroy.timeoutSec", 10)
        .coerceAtLeast(1) * 1000L
    return max(rawDestroyTimeoutMs, getCloseTimeoutMs())
}

private fun getClientIdleTimeoutMs(): Long =
    TeamCityProperties
        .getLong("teamcity.ai.mcp.clientIdleTimeoutMin", 30)
        .coerceAtLeast(1) * 60 * 1000L

private fun getMaxSessionDurationMs(): Long {
    val rawMaxSessionDurationMs = TeamCityProperties
        .getLong("teamcity.ai.mcp.maxSessionDurationMin", 120)
        .coerceAtLeast(1) * 60 * 1000L
    return max(rawMaxSessionDurationMs, getClientIdleTimeoutMs())
}

private fun getThreadPoolSize(default: Int): Int =
    TeamCityProperties.getInteger("teamcity.ai.mcp.threadPoolSize", default)
