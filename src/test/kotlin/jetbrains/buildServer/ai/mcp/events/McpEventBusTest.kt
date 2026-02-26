package jetbrains.buildServer.ai.mcp.events

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class McpEventBusTest {

    @Test
    fun `emit dispatches event to all handlers`() {
        val received = mutableListOf<McpEvent>()
        val handler1 = McpEventHandler { received.add(it) }
        val handler2 = McpEventHandler { received.add(it) }
        val bus = McpEventBus(listOf(handler1, handler2))

        val event = McpEvent.SessionStarted("s1", "client")
        bus.emit(event)

        assertEquals(2, received.size)
        assertTrue(received.all { it === event })
    }

    @Test
    fun `emit with no handlers does not throw`() {
        val bus = McpEventBus(null)
        assertDoesNotThrow { bus.emit(McpEvent.SessionStarted("s1", null)) }
    }

    @Test
    fun `emit with empty handler list does not throw`() {
        val bus = McpEventBus(emptyList())
        assertDoesNotThrow { bus.emit(McpEvent.SessionClosed("s1", "test")) }
    }

    @Test
    fun `exception in one handler does not prevent subsequent handlers from running`() {
        val received = mutableListOf<McpEvent>()
        val failingHandler = McpEventHandler { throw RuntimeException("boom") }
        val goodHandler = McpEventHandler { received.add(it) }
        val bus = McpEventBus(listOf(failingHandler, goodHandler))

        val event = McpEvent.MessageReceived("s1", "tools/list")
        bus.emit(event)

        assertEquals(1, received.size)
        assertEquals(event, received.first())
    }

    @Test
    fun `emit dispatches all event types correctly`() {
        val received = mutableListOf<McpEvent>()
        val handler = McpEventHandler { received.add(it) }
        val bus = McpEventBus(listOf(handler))

        val started = McpEvent.SessionStarted("s1", "info")
        val message = McpEvent.MessageReceived("s1", "tools/call")
        val closed = McpEvent.SessionClosed("s1", "client request")

        bus.emit(started)
        bus.emit(message)
        bus.emit(closed)

        assertEquals(3, received.size)
        assertTrue(received[0] is McpEvent.SessionStarted)
        assertTrue(received[1] is McpEvent.MessageReceived)
        assertTrue(received[2] is McpEvent.SessionClosed)
    }
}
