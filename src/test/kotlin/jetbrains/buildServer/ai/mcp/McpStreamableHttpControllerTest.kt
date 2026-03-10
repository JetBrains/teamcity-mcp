package jetbrains.buildServer.ai.mcp

import io.mockk.*
import jetbrains.buildServer.ai.mcp.events.McpEvent
import jetbrains.buildServer.ai.mcp.events.McpEventBus
import jetbrains.buildServer.ai.mcp.tools.rest.impl.McpToolExecutionContext
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.server.ResponseStatusException
import java.util.Collections
import javax.servlet.http.HttpServletRequest

class McpStreamableHttpControllerTest {

    private lateinit var settingsService: SettingsService
    private lateinit var sessionManager: McpSessionManager
    private lateinit var serverConfigurator: McpServerConfigurator
    private lateinit var eventBus: McpEventBus
    private lateinit var toolExecutionContext: McpToolExecutionContext
    private lateinit var controller: McpStreamableHttpController

    @BeforeEach
    fun setUp() {
        settingsService = mockk()
        sessionManager = mockk(relaxed = true)
        serverConfigurator = mockk()
        eventBus = mockk(relaxed = true)
        toolExecutionContext = McpToolExecutionContext()

        every { settingsService.isMcpServerEnabled() } returns true

        controller = McpStreamableHttpController(
            settingsService = settingsService,
            sessionManager = sessionManager,
            serverConfigurator = serverConfigurator,
            eventBus = eventBus,
            toolExecutionContext = toolExecutionContext
        )
    }

    @AfterEach
    fun tearDown() {
        controller.destroy()
        unmockkAll()
    }

    @Test
    fun `POST accepts application-json with charset`() {
        val ex = assertThrows<ResponseStatusException> {
            post(
                protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
                sessionId = null,
                accept = "application/json, text/event-stream",
                contentType = "application/json; charset=utf-8",
                body = """{"jsonrpc":"2.0","method":"notifications/test"}"""
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
        assertTrue(ex.reason?.contains("Mcp-Session-Id") == true)
    }

    @Test
    fun `POST initialize with session id returns 400`() {
        val ex = assertThrows<ResponseStatusException> {
            post(
                protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
                sessionId = "s1",
                accept = "application/json, text/event-stream",
                contentType = "application/json",
                body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
        assertTrue(ex.reason?.contains("must not include Mcp-Session-Id") == true)
    }

    @Test
    fun `POST without session id for non-initialize returns 400`() {
        val ex = assertThrows<ResponseStatusException> {
            post(
                protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
                sessionId = null,
                accept = "application/json, text/event-stream",
                contentType = "application/json",
                body = """{"jsonrpc":"2.0","method":"notifications/test"}"""
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
        assertTrue(ex.reason?.contains("Mcp-Session-Id") == true)
    }

    @Test
    fun `POST request returns DeferredResult`() {
        val sessionId = "s1"
        val transport = mockk<McpStreamableHttpTransport>(relaxed = true)
        every { sessionManager.getSession(sessionId) } returns transport

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = sessionId,
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        )

        assertTrue(
            result is DeferredResult<*>,
            "POST request should return DeferredResult, got: ${result::class.simpleName}"
        )
    }

    @Test
    fun `POST notification returns DeferredResult`() {
        val sessionId = "s1"
        val transport = mockk<McpStreamableHttpTransport>(relaxed = true)
        every { sessionManager.getSession(sessionId) } returns transport

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = sessionId,
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","method":"notifications/test"}"""
        )

        assertTrue(
            result is DeferredResult<*>,
            "POST notification should return DeferredResult, got: ${result::class.simpleName}"
        )
    }

    @Test
    fun `POST with Accept containing application-json and text-event-stream succeeds`() {
        val sessionId = "s1"
        val transport = mockk<McpStreamableHttpTransport>(relaxed = true)
        every { sessionManager.getSession(sessionId) } returns transport

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = sessionId,
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","method":"notifications/test"}"""
        )

        assertTrue(result is DeferredResult<*>)
    }

    @Test
    fun `POST without Accept header returns 400`() {
        val ex = assertThrows<ResponseStatusException> {
            post(
                protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
                sessionId = "s1",
                accept = null,
                contentType = "application/json",
                body = """{"jsonrpc":"2.0","method":"notifications/test"}"""
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
    }

    @Test
    fun `POST without protocol version for non-initialize is accepted for backward compatibility`() {
        val transport = mockk<McpStreamableHttpTransport>(relaxed = true)
        every { sessionManager.getSession("s1") } returns transport

        val result = post(
            protocolVersion = null,
            sessionId = "s1",
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","method":"notifications/test"}"""
        )

        assertTrue(result is DeferredResult<*>,
            "POST without protocol version should succeed for backward compatibility")
    }

    @Test
    fun `GET returns 405 Method Not Allowed`() {
        val request = mockk<HttpServletRequest>(relaxed = true)
        val result = controller.handleGet(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = null,
            userAgent = null,
            servletRequest = request
        )

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, result.statusCode)
    }

    @Test
    fun `GET without protocol version returns 405`() {
        val request = mockk<HttpServletRequest>(relaxed = true)
        val result = controller.handleGet(
            protocolVersion = null,
            sessionId = null,
            userAgent = null,
            servletRequest = request
        )

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, result.statusCode)
    }

    @Test
    fun `POST invalid json-rpc returns 400`() {
        val sessionId = "s1"
        val transport = mockk<McpStreamableHttpTransport>(relaxed = true)
        every { sessionManager.getSession(sessionId) } returns transport
        coEvery { transport.handlePostMessage(any()) } throws SerializationException("invalid json-rpc")

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = sessionId,
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        )

        val status = awaitErrorStatus(result)
        assertEquals(HttpStatus.BAD_REQUEST, status)
    }

    @Test
    fun `DELETE without protocol version proceeds to session handling`() {
        every { sessionManager.removeSession("unknown") } returns null

        val result = delete(sessionId = "unknown")

        val status = awaitErrorStatus(result as DeferredResult<ResponseEntity<String>>)
        assertEquals(HttpStatus.NOT_FOUND, status)
    }

    @Test
    fun `POST when MCP disabled returns 503`() {
        every { settingsService.isMcpServerEnabled() } returns false

        val ex = assertThrows<ResponseStatusException> {
            post(
                protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
                sessionId = null,
                accept = "application/json",
                contentType = "application/json",
                body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
            )
        }

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.status)
    }

    // --- Content-Type validation ---

    @Test
    fun `POST with text-plain Content-Type returns 400`() {
        val ex = assertThrows<ResponseStatusException> {
            post(
                protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
                sessionId = null,
                accept = "application/json, text/event-stream",
                contentType = "text/plain",
                body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
        assertTrue(ex.reason?.contains("Content-Type") == true)
    }

    @Test
    fun `POST with xml Content-Type returns 400`() {
        val ex = assertThrows<ResponseStatusException> {
            post(
                protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
                sessionId = null,
                accept = "application/json, text/event-stream",
                contentType = "application/xml",
                body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
    }

    // --- JSON parse error ---

    @Test
    fun `POST with malformed JSON body returns 400`() {
        val ex = assertThrows<ResponseStatusException> {
            post(
                protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
                sessionId = null,
                accept = "application/json, text/event-stream",
                contentType = "application/json",
                body = "this is not json"
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
        assertTrue(ex.reason?.contains("Invalid JSON-RPC") == true)
    }

    // --- Protocol version validation ---

    @Test
    fun `POST with unsupported protocol version returns 400`() {
        val ex = assertThrows<ResponseStatusException> {
            post(
                protocolVersion = "1999-01-01",
                sessionId = "s1",
                accept = "application/json, text/event-stream",
                contentType = "application/json",
                body = """{"jsonrpc":"2.0","method":"notifications/test"}"""
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
    }

    @Test
    fun `GET with unsupported protocol version returns 405`() {
        val request = mockk<HttpServletRequest>(relaxed = true)
        val result = controller.handleGet(
            protocolVersion = "1999-01-01",
            sessionId = null,
            userAgent = null,
            servletRequest = request
        )

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, result.statusCode)
    }

    // --- DELETE validation ---

    @Test
    fun `DELETE without session ID returns 400`() {
        val ex = assertThrows<ResponseStatusException> {
            delete(sessionId = null)
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
        assertTrue(ex.reason?.contains("Mcp-Session-Id") == true)
    }

    @Test
    fun `DELETE when MCP disabled returns 503`() {
        every { settingsService.isMcpServerEnabled() } returns false

        val ex = assertThrows<ResponseStatusException> {
            delete(sessionId = "s1")
        }

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.status)
    }

    @Test
    fun `DELETE with unknown session returns 404 via DeferredResult`() {
        every { sessionManager.removeSession("unknown") } returns null

        val result = delete(sessionId = "unknown")

        val status = awaitErrorStatus(result as DeferredResult<ResponseEntity<String>>)
        assertEquals(HttpStatus.NOT_FOUND, status)
    }

    // --- Session recovery for unknown sessions ---

    @Test
    fun `POST request with unknown session creates session and returns DeferredResult`() {
        every { sessionManager.getSession("unknown") } returns null
        mockServerConfigurator()

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = "unknown",
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        )

        assertTrue(result is DeferredResult<*>)
        coVerify(timeout = 2_000) { sessionManager.registerSession(match { it.sessionId == "unknown" }) }
    }

    @Test
    fun `POST notification with unknown session creates session and returns DeferredResult`() {
        every { sessionManager.getSession("unknown") } returns null
        mockServerConfigurator()

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = "unknown",
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","method":"notifications/test"}"""
        )

        assertTrue(result is DeferredResult<*>)
        coVerify(timeout = 2_000) { sessionManager.registerSession(match { it.sessionId == "unknown" }) }
    }

    @Test
    fun `POST request with unknown session returns error when server config fails`() {
        every { sessionManager.getSession("unknown") } returns null
        every { serverConfigurator.configureServer() } throws RuntimeException("config failed")

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = "unknown",
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        )

        val status = awaitErrorStatus(result)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, status)
        verify(timeout = 2_000) { sessionManager.removeSession(any<McpTransportSession>()) }
    }

    @Test
    fun `POST notification with unknown session returns error when server config fails`() {
        every { sessionManager.getSession("unknown") } returns null
        every { serverConfigurator.configureServer() } throws RuntimeException("config failed")

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = "unknown",
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","method":"notifications/test"}"""
        )

        val status = awaitErrorStatus(result)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, status)
        verify(timeout = 2_000) { sessionManager.removeSession(any<McpTransportSession>()) }
    }

    @Test
    fun `POST request with known session does not recreate session`() {
        val sessionId = "existing"
        val transport = mockk<McpStreamableHttpTransport>(relaxed = true)
        every { sessionManager.getSession(sessionId) } returns transport

        post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = sessionId,
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        )

        // Should NOT call configureServer since session already exists
        verify(exactly = 0) { serverConfigurator.configureServer() }
    }

    @Test
    fun `POST request with wrong transport type returns 400`() {
        val wrongTypeSession = mockk<McpTransportSession>(relaxed = true) {
            every { sessionId } returns "wrong-type"
        }
        every { sessionManager.getSession("wrong-type") } returns wrongTypeSession

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = "wrong-type",
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        )

        val status = awaitErrorStatus(result)
        assertEquals(HttpStatus.BAD_REQUEST, status)
    }

    // --- Accept header edge cases ---

    @Test
    fun `POST without application-json in Accept returns 400`() {
        val ex = assertThrows<ResponseStatusException> {
            post(
                protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
                sessionId = "s1",
                accept = "text/event-stream",
                contentType = "application/json",
                body = """{"jsonrpc":"2.0","method":"notifications/test"}"""
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
    }

    // --- Request ID validation (MCP spec: id is mandatory for requests) ---

    @Test
    fun `POST initialize with invalid id returns 400`() {
        val ex = assertThrows<ResponseStatusException> {
            post(
                protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
                sessionId = null,
                accept = "application/json, text/event-stream",
                contentType = "application/json",
                body = """{"jsonrpc":"2.0","id":true,"method":"initialize","params":{}}"""
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
        assertTrue(ex.reason?.contains("id") == true)
    }

    @Test
    fun `POST request with invalid id returns 400`() {
        val ex = assertThrows<ResponseStatusException> {
            post(
                protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
                sessionId = "s1",
                accept = "application/json, text/event-stream",
                contentType = "application/json",
                body = """{"jsonrpc":"2.0","id":true,"method":"tools/list","params":{}}"""
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.status)
        assertTrue(ex.reason?.contains("id") == true)
    }

    // --- Initialize can omit protocol version (required=false for init) ---

    @Test
    fun `POST initialize without protocol version is accepted`() {
        val result = post(
            protocolVersion = null,
            sessionId = null,
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
        )

        assertTrue(result is DeferredResult<*>)
    }

    // --- GET with sensitive headers ---

    @Test
    fun `GET with Authorization header returns 405 without throwing`() {
        val request = mockk<HttpServletRequest>(relaxed = true)
        every { request.headerNames } returns Collections.enumeration(
            listOf("Authorization", "User-Agent")
        )
        every { request.getHeader("Authorization") } returns "Bearer secret-token"
        every { request.getHeader("User-Agent") } returns "TestAgent"

        val result = controller.handleGet(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = "s1",
            userAgent = "TestAgent",
            servletRequest = request
        )

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, result.statusCode)
    }

    // --- Initialize failure cleanup ---

    @Test
    fun `POST initialize cleans up session when server configuration throws`() {
        every { serverConfigurator.configureServer() } throws RuntimeException("config failed")

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = null,
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
        )

        val status = awaitErrorStatus(result)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, status)

        // Verify cleanup was attempted — removeSession should be called for the new session
        verify(timeout = 2_000) { sessionManager.removeSession(any<McpTransportSession>()) }
    }

    @Test
    fun `POST initialize returns error when server configuration throws`() {
        every { serverConfigurator.configureServer() } throws RuntimeException("server setup failed")

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = null,
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
        )

        val status = awaitErrorStatus(result)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, status)
    }

    @Test
    fun `POST initialize emits SessionStarted when createSession succeeds`() {
        mockServerConfigurator()

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = null,
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
        )

        // Wait for async flow (will error since mock server can't handle init)
        awaitErrorStatus(result)

        verify(timeout = 2_000) {
            eventBus.emit(match { it is McpEvent.SessionStarted })
        }
    }

    @Test
    fun `POST initialize does not emit SessionStarted when createSession itself fails`() {
        every { serverConfigurator.configureServer() } throws RuntimeException("config failed")

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = null,
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
        )

        awaitErrorStatus(result)

        verify(exactly = 0) {
            eventBus.emit(match { it is McpEvent.SessionStarted })
        }
    }

    @Test
    fun `POST initialize failure after session start emits SessionClosed`() {
        mockServerConfigurator()

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = null,
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
        )

        awaitErrorStatus(result)

        verify(timeout = 2_000) {
            eventBus.emit(match {
                it is McpEvent.SessionStarted
            })
        }

        verify(timeout = 2_000) {
            eventBus.emit(match {
                it is McpEvent.SessionClosed &&
                    (it.reason == "Initialize request failed" || it.reason == "Initialize request cancelled")
            })
        }
    }

    // --- SessionClosed emitted on termination ---

    @Test
    fun `DELETE returns 200 and emits SessionClosed when session is found`() {
        val transport = mockk<McpStreamableHttpTransport>(relaxed = true)
        every { transport.sessionId } returns "s1"
        every { sessionManager.removeSession("s1") } returns transport

        val result = delete(sessionId = "s1")

        val deadline = System.currentTimeMillis() + 2_000
        while (!result.hasResult() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        @Suppress("UNCHECKED_CAST")
        val response = result.result as? ResponseEntity<Void>
        assertEquals(HttpStatus.OK, response?.statusCode)

        verify(timeout = 2_000) {
            eventBus.emit(match {
                it is McpEvent.SessionClosed &&
                it.sessionId == "s1" &&
                it.reason == "client request"
            })
        }
    }

    @Test
    fun `destroy emits SessionClosed for all active sessions`() {
        val transport1 = mockk<McpStreamableHttpTransport>(relaxed = true)
        val transport2 = mockk<McpStreamableHttpTransport>(relaxed = true)
        every { transport1.sessionId } returns "s1"
        every { transport2.sessionId } returns "s2"
        every { sessionManager.getAllSessions() } returns setOf(transport1, transport2)
        every { sessionManager.removeSession(transport1) } returns true
        every { sessionManager.removeSession(transport2) } returns true

        controller.destroy()

        verify(exactly = 2) {
            eventBus.emit(match {
                it is McpEvent.SessionClosed &&
                    it.reason == "controller destroy" &&
                    (it.sessionId == "s1" || it.sessionId == "s2")
            })
        }
    }

    // --- Session replacement ---

    @Test
    fun `createSession closes old session when registerSession returns replaced session`() {
        every { sessionManager.getSession("recovered") } returns null
        mockServerConfigurator()

        val oldTransport = mockk<McpTransportSession>(relaxed = true) {
            every { sessionId } returns "recovered"
        }
        every { sessionManager.registerSession(any()) } returns oldTransport

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = "recovered",
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        )

        // Wait for async flow
        val deadline = System.currentTimeMillis() + 2_000
        while (!result.hasResult() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        // Old session must be closed
        coVerify(timeout = 2_000) { oldTransport.close() }

        // SessionClosed emitted for the replaced session
        verify(timeout = 2_000) {
            eventBus.emit(match {
                it is McpEvent.SessionClosed &&
                    it.sessionId == "recovered" &&
                    it.reason == "replaced by new session"
            })
        }
    }

    // --- createSession error does not emit SessionClosed ---

    @Test
    fun `createSession error does not emit SessionClosed when SessionStarted was not emitted`() {
        every { serverConfigurator.configureServer() } throws RuntimeException("config failed")

        val result = post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = null,
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
        )

        awaitErrorStatus(result)

        verify(exactly = 0) {
            eventBus.emit(match { it is McpEvent.SessionStarted })
        }
        verify(exactly = 0) {
            eventBus.emit(match { it is McpEvent.SessionClosed })
        }
    }

    // --- destroy when session already removed by callback ---

    @Test
    fun `destroy still closes transport and emits SessionClosed when removeSession returns false`() {
        val transport = mockk<McpStreamableHttpTransport>(relaxed = true)
        every { transport.sessionId } returns "s1"
        every { sessionManager.getAllSessions() } returns setOf(transport)
        every { sessionManager.removeSession(transport) } returns false

        controller.destroy()

        coVerify { transport.close() }
        verify {
            eventBus.emit(match {
                it is McpEvent.SessionClosed &&
                    it.sessionId == "s1" &&
                    it.reason == "controller destroy"
            })
        }
    }

    // --- InitializeRequested event ---

    @Test
    fun `POST initialize emits InitializeRequested event with protocol version and client info`() {
        mockServerConfigurator()

        post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = null,
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0"}}}"""
        )

        verify {
            eventBus.emit(match {
                it is McpEvent.InitializeRequested &&
                it.protocolVersion == "2025-11-25" &&
                it.clientInfo != null && it.clientInfo!!.contains("test-client")
            })
        }
    }

    @Test
    fun `POST initialize emits InitializeRequested event even when validation rejects the request`() {
        assertThrows<ResponseStatusException> {
            post(
                protocolVersion = "1999-01-01",
                sessionId = null,
                accept = "application/json, text/event-stream",
                contentType = "application/json",
                body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01","capabilities":{},"clientInfo":{"name":"bad-client","version":"0.1"}}}"""
            )
        }

        verify {
            eventBus.emit(match {
                it is McpEvent.InitializeRequested &&
                it.protocolVersion == "1999-01-01" &&
                it.clientInfo != null && it.clientInfo!!.contains("bad-client")
            })
        }
    }

    @Test
    fun `POST initialize emits InitializeRequested event even when accept header is rejected`() {
        assertThrows<ResponseStatusException> {
            post(
                protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
                sessionId = null,
                accept = "text/plain",
                contentType = "application/json",
                body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{}}}"""
            )
        }

        verify {
            eventBus.emit(match {
                it is McpEvent.InitializeRequested &&
                it.protocolVersion == "2025-11-25"
            })
        }
    }

    @Test
    fun `POST non-initialize request does not emit InitializeRequested event`() {
        val transport = mockk<McpStreamableHttpTransport>(relaxed = true)
        every { sessionManager.getSession("s1") } returns transport

        post(
            protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
            sessionId = "s1",
            accept = "application/json, text/event-stream",
            contentType = "application/json",
            body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        )

        verify(exactly = 0) {
            eventBus.emit(match { it is McpEvent.InitializeRequested })
        }
    }

    private fun post(
        protocolVersion: String?,
        sessionId: String?,
        accept: String?,
        contentType: String,
        body: String
    ): DeferredResult<ResponseEntity<String>> {
        val servletRequest = mockk<HttpServletRequest>(relaxed = true)
        return controller.handlePost(
            protocolVersion = protocolVersion,
            sessionId = sessionId,
            accept = accept,
            contentType = contentType,
            body = body,
            servletRequest = servletRequest
        )
    }

    private fun delete(sessionId: String?): DeferredResult<ResponseEntity<Void>> {
        val servletRequest = mockk<HttpServletRequest>(relaxed = true)
        return controller.handleDelete(
            sessionId = sessionId,
            servletRequest = servletRequest
        )
    }

    private fun mockServerConfigurator() {
        val server = mockk<Server>(relaxed = true)
        every { serverConfigurator.configureServer() } returns server
    }

    private fun awaitErrorStatus(result: DeferredResult<ResponseEntity<String>>): HttpStatus {
        val deadline = System.currentTimeMillis() + 2_000
        while (!result.hasResult() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        val error = result.result as? ResponseStatusException
            ?: error("Expected ResponseStatusException result")
        return error.status
    }
}
