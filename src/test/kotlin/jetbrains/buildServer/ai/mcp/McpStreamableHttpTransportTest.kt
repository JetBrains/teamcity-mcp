package jetbrains.buildServer.ai.mcp

import io.mockk.*
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class McpStreamableHttpTransportTest {

    private val executorService = Executors.newSingleThreadExecutor()
    private lateinit var transport: McpStreamableHttpTransport

    @BeforeEach
    fun setUp() {
        transport = McpStreamableHttpTransport(
            sessionId = "test-session",
            executorService = executorService,
            maxSessionDurationMs = 60_000L,
            clientIdleTimeoutMs = 30_000L
        )
    }

    @AfterEach
    fun tearDown() {
        runBlocking { transport.close() }
        executorService.shutdownNow()
        unmockkAll()
    }

    // --- Constructor validation ---

    @Test
    fun `blank sessionId throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            McpStreamableHttpTransport(
                sessionId = "  ",
                executorService = executorService
            )
        }
    }

    // --- Start / close lifecycle ---

    @Test
    fun `isOpen returns false before start`() {
        assertFalse(transport.isOpen())
    }

    @Test
    fun `isOpen returns true after start`() = runBlocking {
        transport.start()
        assertTrue(transport.isOpen())
    }

    @Test
    fun `start is idempotent - second start calls onError`() = runBlocking {
        var errorReceived: Throwable? = null
        transport.onError { errorReceived = it }
        transport.start()
        try {
            transport.start()
        } catch (_: Exception) {
        }
        assertNotNull(errorReceived)
    }

    @Test
    fun `close changes isOpen from true to false`() = runBlocking {
        transport.start()
        assertTrue(transport.isOpen())
        transport.close()
        assertFalse(transport.isOpen())
    }

    @Test
    fun `close is idempotent`() = runBlocking {
        var closeCount = 0
        transport.onClose { closeCount++ }
        transport.start()
        transport.close()
        transport.close()
        assertEquals(1, closeCount)
    }

    @Test
    fun `close completes pending deferreds with error`() = runBlocking {
        transport.start()

        val requestId = RequestId(1L)
        val deferred = transport.captureResponse(requestId)

        transport.close()

        assertTrue(deferred.isCancelled, "Pending deferred should be cancelled on close")
    }

    // --- captureResponse + send routing ---

    @Test
    fun `captureResponse completes when send called with matching response`() = runBlocking {
        transport.start()

        val requestId = RequestId(42L)
        val deferred = transport.captureResponse(requestId)

        val response = JSONRPCResponse(id = requestId)
        transport.send(response)

        assertTrue(deferred.isCompleted)
        assertEquals(response, deferred.await())
    }

    @Test
    fun `captureResponse works with JSONRPCError`() = runBlocking {
        transport.start()

        val requestId = RequestId(42L)
        val deferred = transport.captureResponse(requestId)

        val error = JSONRPCError(
            id = requestId,
            error = RPCError(code = -32600, message = "Invalid Request")
        )
        transport.send(error)

        assertTrue(deferred.isCompleted)
        assertEquals(error, deferred.await())
    }

    @Test
    fun `send removes deferred after completing (one-shot)`() = runBlocking {
        transport.start()

        val requestId = RequestId(42L)
        val deferred = transport.captureResponse(requestId)

        transport.send(JSONRPCResponse(id = requestId))
        assertTrue(deferred.isCompleted)

        // Second send to the same requestId should not throw
        transport.send(JSONRPCResponse(id = requestId))
        // No error — just dropped silently
    }

    @Test
    fun `multiple concurrent captureResponse deferreds work independently`() = runBlocking {
        transport.start()

        val deferred1 = transport.captureResponse(RequestId(1L))
        val deferred2 = transport.captureResponse(RequestId(2L))

        // Complete request 2 first
        transport.send(JSONRPCResponse(id = RequestId(2L)))

        assertTrue(deferred2.isCompleted)
        assertFalse(deferred1.isCompleted)

        // Now complete request 1
        transport.send(JSONRPCResponse(id = RequestId(1L)))

        assertTrue(deferred1.isCompleted)
    }

    @Test
    fun `send to unknown requestId does not throw`() = runBlocking {
        transport.start()

        // No captureResponse called — send should just drop the message
        transport.send(JSONRPCResponse(id = RequestId(999L)))
        // No exception = success
    }

    @Test
    fun `captureResponse deferred cancelled on close`() = runBlocking {
        transport.start()

        val deferred = transport.captureResponse(RequestId(1L))
        assertFalse(deferred.isCompleted)

        transport.close()

        assertTrue(deferred.isCancelled)
    }

    // --- handlePostMessage ---

    @Test
    fun `handlePostMessage invokes onMessage handler`() = runBlocking {
        transport.start()
        var receivedMessage: JSONRPCMessage? = null
        transport.onMessage { receivedMessage = it }
        transport.handlePostMessage("""{"jsonrpc":"2.0","method":"test/notification"}""")
        assertNotNull(receivedMessage)
    }

    @Test
    fun `handlePostMessage when closed calls onError`() = runBlocking {
        var errorReceived: Throwable? = null
        transport.onError { errorReceived = it }
        try {
            transport.handlePostMessage("""{"jsonrpc":"2.0","method":"test/notification"}""")
        } catch (_: Exception) {
        }
        assertNotNull(errorReceived)
    }

    // --- cancelResponse ---

    @Test
    fun `cancelResponse cancels and removes the deferred`() = runBlocking {
        transport.start()

        val requestId = RequestId(10L)
        val deferred = transport.captureResponse(requestId)
        assertFalse(deferred.isCancelled)

        transport.cancelResponse(requestId, "timeout")

        assertTrue(deferred.isCancelled)

        // Subsequent send to the same requestId should be silently dropped (deferred removed)
        transport.send(JSONRPCResponse(id = requestId))
        // No error = success
    }

    @Test
    fun `cancelResponse for non-existent requestId does not throw`() = runBlocking {
        transport.start()
        // No captureResponse called
        transport.cancelResponse(RequestId(999L), "no-op")
        // No exception = success
    }

    // --- String-based RequestIds ---

    @Test
    fun `captureResponse works with string RequestId`() = runBlocking {
        transport.start()

        val requestId = RequestId("abc-123")
        val deferred = transport.captureResponse(requestId)

        val response = JSONRPCResponse(id = requestId)
        transport.send(response)

        assertTrue(deferred.isCompleted)
        assertEquals(response, deferred.await())
    }

    @Test
    fun `string and numeric RequestIds with same value share the same key`() = runBlocking {
        transport.start()

        // First registration for "42" (string)
        val stringDeferred = transport.captureResponse(RequestId("42"))
        // Second registration for 42 (number) overwrites the first since key is "req-42" for both
        val numberDeferred = transport.captureResponse(RequestId(42L))

        transport.send(JSONRPCResponse(id = RequestId(42L)))

        // The number deferred (most recent) should be completed
        assertTrue(numberDeferred.isCompleted)
        // The old deferred should be cancelled (not leaked)
        assertTrue(stringDeferred.isCancelled, "Old deferred should be cancelled when replaced")
    }

    @Test
    fun `distinct string RequestIds are independent`() = runBlocking {
        transport.start()

        val deferred1 = transport.captureResponse(RequestId("alpha"))
        val deferred2 = transport.captureResponse(RequestId("beta"))

        transport.send(JSONRPCResponse(id = RequestId("beta")))

        assertTrue(deferred2.isCompleted)
        assertFalse(deferred1.isCompleted)

        transport.send(JSONRPCResponse(id = RequestId("alpha")))
        assertTrue(deferred1.isCompleted)
    }

    // --- initSessionTimeoutWatchdog ---

    @Test
    fun `watchdog closes transport when max session duration exceeded`() = runBlocking {
        val shortLivedTransport = McpStreamableHttpTransport(
            sessionId = "watchdog-test-max",
            executorService = executorService,
            maxSessionDurationMs = 100L,  // very short
            clientIdleTimeoutMs = 60_000L
        )
        shortLivedTransport.start()
        assertTrue(shortLivedTransport.isOpen())

        shortLivedTransport.initSessionTimeoutWatchdog(kotlin.time.Duration.parse("50ms"))

        // Wait enough for the watchdog to fire
        Thread.sleep(500)

        assertFalse(shortLivedTransport.isOpen(), "Transport should be closed after max session duration exceeded")
    }

    @Test
    fun `watchdog closes transport when client idle timeout exceeded`() = runBlocking {
        val idleTransport = McpStreamableHttpTransport(
            sessionId = "watchdog-test-idle",
            executorService = executorService,
            maxSessionDurationMs = 60_000L,
            clientIdleTimeoutMs = 100L  // very short idle timeout
        )
        idleTransport.start()
        assertTrue(idleTransport.isOpen())

        idleTransport.initSessionTimeoutWatchdog(kotlin.time.Duration.parse("50ms"))

        // Don't send any messages — let the idle timeout fire
        Thread.sleep(500)

        assertFalse(idleTransport.isOpen(), "Transport should be closed after client idle timeout exceeded")
    }

    @Test
    fun `watchdog does not close transport when client is active`() = runBlocking {
        val activeTransport = McpStreamableHttpTransport(
            sessionId = "watchdog-test-active",
            executorService = executorService,
            maxSessionDurationMs = 60_000L,
            clientIdleTimeoutMs = 200L
        )
        activeTransport.start()
        activeTransport.onMessage { /* consume */ }

        activeTransport.initSessionTimeoutWatchdog(kotlin.time.Duration.parse("50ms"))

        // Keep sending messages to prevent idle timeout
        repeat(5) {
            Thread.sleep(100)
            activeTransport.handlePostMessage("""{"jsonrpc":"2.0","method":"test/notification"}""")
        }

        assertTrue(activeTransport.isOpen(), "Transport should remain open when client is active")
        activeTransport.close()
    }

    // --- onSessionExpired callback ---

    @Test
    fun `watchdog invokes onExpired callback on max duration timeout`() = runBlocking {
        val shortLivedTransport = McpStreamableHttpTransport(
            sessionId = "expire-cb-max",
            executorService = executorService,
            maxSessionDurationMs = 100L,
            clientIdleTimeoutMs = 60_000L
        )
        shortLivedTransport.start()

        var expiredReason: String? = null
        shortLivedTransport.initSessionTimeoutWatchdog(kotlin.time.Duration.parse("50ms")) { reason ->
            expiredReason = reason
        }
        Thread.sleep(500)

        assertNotNull(expiredReason, "onExpired should have been called")
        assertTrue(expiredReason!!.contains("max session duration"), "Reason should mention max duration, got: $expiredReason")
        // Transport stays open — the callback is responsible for closing it
        assertTrue(shortLivedTransport.isOpen(), "Transport should remain open (callback handles closing)")
        shortLivedTransport.close()
    }

    @Test
    fun `watchdog invokes onExpired callback on idle timeout`() = runBlocking {
        val idleTransport = McpStreamableHttpTransport(
            sessionId = "expire-cb-idle",
            executorService = executorService,
            maxSessionDurationMs = 60_000L,
            clientIdleTimeoutMs = 100L
        )
        idleTransport.start()

        var expiredReason: String? = null
        idleTransport.initSessionTimeoutWatchdog(kotlin.time.Duration.parse("50ms")) { reason ->
            expiredReason = reason
        }
        Thread.sleep(500)

        assertNotNull(expiredReason, "onExpired should have been called")
        assertTrue(expiredReason!!.contains("idle timeout"), "Reason should mention idle timeout, got: $expiredReason")
        assertTrue(idleTransport.isOpen(), "Transport should remain open (callback handles closing)")
        idleTransport.close()
    }

    @Test
    fun `watchdog falls back to close when no onExpired callback provided`() = runBlocking {
        val shortLivedTransport = McpStreamableHttpTransport(
            sessionId = "expire-fallback",
            executorService = executorService,
            maxSessionDurationMs = 100L,
            clientIdleTimeoutMs = 60_000L
        )
        shortLivedTransport.start()

        shortLivedTransport.initSessionTimeoutWatchdog(kotlin.time.Duration.parse("50ms"))
        Thread.sleep(500)

        assertFalse(shortLivedTransport.isOpen(), "Transport should be closed when no callback is provided")
    }

    @Test
    fun `watchdog falls back to close when onExpired callback throws`() = runBlocking {
        val shortLivedTransport = McpStreamableHttpTransport(
            sessionId = "expire-throw",
            executorService = executorService,
            maxSessionDurationMs = 100L,
            clientIdleTimeoutMs = 60_000L
        )
        shortLivedTransport.start()

        shortLivedTransport.initSessionTimeoutWatchdog(kotlin.time.Duration.parse("50ms")) {
            throw RuntimeException("callback failed")
        }
        Thread.sleep(500)

        assertFalse(shortLivedTransport.isOpen(), "Transport should be closed as fallback when callback throws")
    }

    // --- close robustness ---

    @Test
    fun `close succeeds even when onClose callback throws`() = runBlocking {
        transport.start()
        transport.onClose { throw RuntimeException("onClose failed") }

        val deferred = transport.captureResponse(RequestId(1L))

        // close should not throw despite the onClose callback failure
        transport.close()

        assertFalse(transport.isOpen())
        assertTrue(deferred.isCancelled, "Pending deferred should still be cancelled")
    }

    // --- send when closed ---

    @Test
    fun `send message when transport is closed calls onError`() = runBlocking {
        var errorReceived: Throwable? = null
        transport.onError { errorReceived = it }
        val msg = JSONRPCNotification(method = "test/notification")
        try {
            transport.send(msg)
        } catch (_: Exception) {
        }
        assertNotNull(errorReceived)
    }

    // --- captureResponse duplicate handling ---

    @Test
    fun `double captureResponse with same requestId cancels old deferred`() = runBlocking {
        transport.start()

        val requestId = RequestId(77L)
        val oldDeferred = transport.captureResponse(requestId)
        val newDeferred = transport.captureResponse(requestId)

        // Old deferred should be cancelled
        assertTrue(oldDeferred.isCancelled, "Old deferred should be cancelled when replaced")
        assertFalse(newDeferred.isCancelled, "New deferred should not be cancelled")

        // New deferred should still work
        transport.send(JSONRPCResponse(id = requestId))
        assertTrue(newDeferred.isCompleted, "New deferred should be completed by send")
    }

    // --- malformed message does not reset idle timeout ---

    @Test
    fun `malformed message does not reset idle timeout`() = runBlocking {
        val idleTransport = McpStreamableHttpTransport(
            sessionId = "idle-malformed-test",
            executorService = executorService,
            maxSessionDurationMs = 60_000L,
            clientIdleTimeoutMs = 200L
        )
        idleTransport.start()
        idleTransport.onMessage { /* consume */ }
        idleTransport.onError { /* consume */ }

        idleTransport.initSessionTimeoutWatchdog(kotlin.time.Duration.parse("50ms"))

        // Keep sending malformed messages — these should NOT reset the idle timer
        repeat(5) {
            Thread.sleep(100)
            try {
                idleTransport.handlePostMessage("this is not json")
            } catch (_: Exception) {
                // Expected — malformed JSON
            }
        }

        assertFalse(idleTransport.isOpen(), "Transport should be closed — malformed messages must not reset idle timeout")
    }

    // --- onError callback that throws ---

    @Test
    fun `onError callback throwing does not prevent original exception from propagating`() = runBlocking {
        transport.start()
        transport.onError { throw RuntimeException("onError callback exploded") }

        val exception = assertThrows(Exception::class.java) {
            runBlocking { transport.handlePostMessage("this is not json") }
        }

        // The original parsing exception should propagate, not the callback's exception
        assertFalse(
            exception.message?.contains("onError callback exploded") == true,
            "Original exception should propagate, not the callback's exception"
        )
    }

    @Test
    fun `respondWithError propagates even when onError callback throws`() = runBlocking {
        // Transport not started — respondWithError will be called
        transport.onError { throw RuntimeException("onError callback exploded") }

        val exception = assertThrows(Exception::class.java) {
            runBlocking { transport.handlePostMessage("""{"jsonrpc":"2.0","method":"test"}""") }
        }

        // The ResponseStatusException from respondWithError should propagate
        assertTrue(
            exception.message?.contains("Transport not established") == true,
            "ResponseStatusException should propagate, got: ${exception.message}"
        )
    }

    // --- Cross-thread cancelResponse (simulates timeout handler on Servlet thread) ---

    @Test
    fun `cancelResponse from a different thread cancels deferred`() = runBlocking {
        transport.start()

        val requestId = RequestId(1L)
        val deferred = transport.captureResponse(requestId)

        val latch = CountDownLatch(1)
        val thread = Thread {
            transport.cancelResponse(requestId, "HTTP timeout from Servlet thread")
            latch.countDown()
        }
        thread.start()

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Cancel thread should complete")
        assertTrue(deferred.isCancelled, "Deferred should be cancelled by cross-thread cancelResponse")
    }

    @Test
    fun `AtomicReference transport pattern - timeout handler sees transport across threads`() = runBlocking {
        transport.start()

        val transportRef = AtomicReference<McpStreamableHttpTransport?>(null)
        val requestId = RequestId(42L)

        // Simulate coroutine: publish transport, then register deferred
        transportRef.set(transport)
        val deferred = transport.captureResponse(requestId)

        // Simulate timeout handler on a different thread reading the AtomicReference
        val cancelledRef = AtomicReference(false)
        val latch = CountDownLatch(1)
        val timeoutThread = Thread {
            val t = transportRef.get()
            t?.cancelResponse(requestId, "timeout")
            cancelledRef.set(t != null)
            latch.countDown()
        }
        timeoutThread.start()

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Timeout thread should complete")
        assertTrue(cancelledRef.get(), "Timeout handler should see the transport via AtomicReference")
        assertTrue(deferred.isCancelled, "Deferred should be cancelled")
    }

    @Test
    fun `concurrent captureResponse and cancelResponse do not lose deferreds`() = runBlocking {
        transport.start()

        val iterations = 50
        val allCancelled = AtomicReference(true)
        val allDone = CountDownLatch(iterations)

        repeat(iterations) { i ->
            val requestId = RequestId(i.toLong())
            val deferred = transport.captureResponse(requestId)

            // Cancel from a separate thread (simulates timeout handler)
            Thread {
                transport.cancelResponse(requestId, "timeout $i")
                if (!deferred.isCancelled) {
                    allCancelled.set(false)
                }
                allDone.countDown()
            }.start()
        }

        assertTrue(allDone.await(5, TimeUnit.SECONDS), "All cancel threads should complete")
        assertTrue(allCancelled.get(), "All deferreds should be cancelled by their respective threads")
    }
}
