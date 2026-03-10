package jetbrains.buildServer.ai.mcp

import io.mockk.*
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class McpSessionManagerTest {

    private lateinit var sessionManager: McpSessionManager

    @BeforeEach
    fun setUp() {
        sessionManager = McpSessionManager()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun createMockSession(sessionId: String = "test-session-123", isOpen: Boolean = true): McpTransportSession {
        return mockk<McpTransportSession>(relaxed = true) {
            every { this@mockk.sessionId } returns sessionId
            every { this@mockk.isOpen() } returns isOpen
            coEvery { send(any<JSONRPCMessage>()) } just Runs
            coEvery { close() } just Runs
        }
    }

    @Test
    fun `registerSession adds session to manager`() {
        val session = createMockSession("session-1")

        val old = sessionManager.registerSession(session)

        assertNull(old)
        assertEquals(1, sessionManager.getSessionCount())
        assertNotNull(sessionManager.getSession("session-1"))
    }

    @Test
    fun `getSession returns correct session by ID`() {
        val session1 = createMockSession("session-1")
        val session2 = createMockSession("session-2")

        sessionManager.registerSession(session1)
        sessionManager.registerSession(session2)

        val retrieved = sessionManager.getSession("session-1")
        assertEquals(session1, retrieved)
        assertEquals("session-1", retrieved?.sessionId)
    }

    @Test
    fun `getSession returns null for non-existent session`() {
        val retrieved = sessionManager.getSession("non-existent")
        assertNull(retrieved)
    }

    @Test
    fun `removeSession removes and returns session`() {
        val session = createMockSession("session-1")
        sessionManager.registerSession(session)

        val removed = sessionManager.removeSession("session-1")

        assertEquals(session, removed)
        assertEquals(0, sessionManager.getSessionCount())
        assertNull(sessionManager.getSession("session-1"))
    }

    @Test
    fun `removeSession returns null for non-existent session`() {
        val removed = sessionManager.removeSession("non-existent")
        assertNull(removed)
    }

    @Test
    fun `getAllSessionIds returns all session IDs`() {
        val session1 = createMockSession("session-1")
        val session2 = createMockSession("session-2")
        val session3 = createMockSession("session-3")

        sessionManager.registerSession(session1)
        sessionManager.registerSession(session2)
        sessionManager.registerSession(session3)

        val sessionIds = sessionManager.getAllSessionIds()
        assertEquals(3, sessionIds.size)
        assertTrue(sessionIds.contains("session-1"))
        assertTrue(sessionIds.contains("session-2"))
        assertTrue(sessionIds.contains("session-3"))
    }

    @Test
    fun `getSessionCount returns correct count`() {
        assertEquals(0, sessionManager.getSessionCount())

        sessionManager.registerSession(createMockSession("session-1"))
        assertEquals(1, sessionManager.getSessionCount())

        sessionManager.registerSession(createMockSession("session-2"))
        assertEquals(2, sessionManager.getSessionCount())

        sessionManager.removeSession("session-1")
        assertEquals(1, sessionManager.getSessionCount())
    }

    @Test
    fun `closeAll closes all sessions`() = runBlocking {
        val session1 = createMockSession("session-1")
        val session2 = createMockSession("session-2")
        val session3 = createMockSession("session-3")

        sessionManager.registerSession(session1)
        sessionManager.registerSession(session2)
        sessionManager.registerSession(session3)

        sessionManager.closeAll()

        // Verify all sessions were closed
        coVerify { session1.close() }
        coVerify { session2.close() }
        coVerify { session3.close() }

        // Verify sessions were cleared
        assertEquals(0, sessionManager.getSessionCount())
    }

    @Test
    fun `closeAll handles exceptions gracefully`() = runBlocking {
        val failingSession = mockk<McpTransportSession>(relaxed = true) {
            every { sessionId } returns "failing-session"
            every { isOpen() } returns true
            coEvery { close() } throws RuntimeException("Close failed")
        }
        val goodSession = createMockSession("good-session")

        sessionManager.registerSession(failingSession)
        sessionManager.registerSession(goodSession)

        // Should not throw exception
        sessionManager.closeAll()

        // Both sessions should be removed from manager
        assertEquals(0, sessionManager.getSessionCount())
    }

    @Test
    fun `cleanupClosedSessions removes only closed sessions`() {
        val openSession1 = createMockSession("open-1", isOpen = true)
        val openSession2 = createMockSession("open-2", isOpen = true)
        val closedSession1 = createMockSession("closed-1", isOpen = false)
        val closedSession2 = createMockSession("closed-2", isOpen = false)

        sessionManager.registerSession(openSession1)
        sessionManager.registerSession(closedSession1)
        sessionManager.registerSession(openSession2)
        sessionManager.registerSession(closedSession2)

        val removedCount = sessionManager.cleanupClosedSessions()

        assertEquals(2, removedCount)
        assertEquals(2, sessionManager.getSessionCount())
        assertNotNull(sessionManager.getSession("open-1"))
        assertNotNull(sessionManager.getSession("open-2"))
        assertNull(sessionManager.getSession("closed-1"))
        assertNull(sessionManager.getSession("closed-2"))
    }

    @Test
    fun `cleanupClosedSessions returns zero when all sessions are open`() {
        sessionManager.registerSession(createMockSession("session-1", isOpen = true))
        sessionManager.registerSession(createMockSession("session-2", isOpen = true))

        val removedCount = sessionManager.cleanupClosedSessions()

        assertEquals(0, removedCount)
        assertEquals(2, sessionManager.getSessionCount())
    }

    @Test
    fun `concurrent registration of sessions is thread-safe`() {
        val threads = (1..10).map { threadNum ->
            Thread {
                repeat(10) { iteration ->
                    val sessionId = "session-$threadNum-$iteration"
                    sessionManager.registerSession(createMockSession(sessionId))
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(100, sessionManager.getSessionCount())
    }

    @Test
    fun `multiple registrations with same session ID replaces previous session`() {
        val session1 = createMockSession("same-id")
        val session2 = createMockSession("same-id")

        sessionManager.registerSession(session1)
        val old = sessionManager.registerSession(session2)

        assertEquals(session1, old)
        assertEquals(1, sessionManager.getSessionCount())
        assertEquals(session2, sessionManager.getSession("same-id"))
    }

    @Test
    fun `registerSession returns old session without closing it`() {
        val oldSession = createMockSession("same-id", isOpen = true)
        val newSession = createMockSession("same-id", isOpen = true)

        sessionManager.registerSession(oldSession)
        val returned = sessionManager.registerSession(newSession)

        // Old session is returned but NOT closed — caller is responsible
        assertEquals(oldSession, returned)
        coVerify(exactly = 0) { oldSession.close() }

        // New session should be registered
        assertEquals(1, sessionManager.getSessionCount())
        assertEquals(newSession, sessionManager.getSession("same-id"))
    }

    @Test
    fun `getAllSessions returns all session objects`() {
        val session1 = createMockSession("session-1")
        val session2 = createMockSession("session-2")

        sessionManager.registerSession(session1)
        sessionManager.registerSession(session2)

        val sessions = sessionManager.getAllSessions()
        assertEquals(2, sessions.size)
        assertTrue(sessions.contains(session1))
        assertTrue(sessions.contains(session2))
    }

    @Test
    fun `removeSession with identity match removes only if same instance`() {
        val session1 = createMockSession("same-id")
        val session2 = createMockSession("same-id")

        sessionManager.registerSession(session1)

        // Should NOT remove: wrong instance
        assertFalse(sessionManager.removeSession(session2))
        assertEquals(1, sessionManager.getSessionCount())

        // Should remove: correct instance
        assertTrue(sessionManager.removeSession(session1))
        assertEquals(0, sessionManager.getSessionCount())
    }

    @Test
    fun `removeSession with identity match returns false for replaced session`() {
        val oldSession = createMockSession("same-id")
        val newSession = createMockSession("same-id")

        sessionManager.registerSession(oldSession)
        sessionManager.registerSession(newSession)

        // Old session's callback trying to remove — should fail (new session is in the map)
        assertFalse(sessionManager.removeSession(oldSession))
        // New session still there
        assertEquals(newSession, sessionManager.getSession("same-id"))
    }
}
