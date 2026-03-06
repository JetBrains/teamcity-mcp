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

        val initRequested = McpEvent.InitializeRequested("2025-11-25", """{"name":"test","version":"1.0"}""")
        val started = McpEvent.SessionStarted("s1", "info")
        val message = McpEvent.MessageReceived("s1", "tools/call")
        val closed = McpEvent.SessionClosed("s1", "client request")

        bus.emit(initRequested)
        bus.emit(started)
        bus.emit(message)
        bus.emit(closed)

        assertEquals(4, received.size)
        assertTrue(received[0] is McpEvent.InitializeRequested)
        assertTrue(received[1] is McpEvent.SessionStarted)
        assertTrue(received[2] is McpEvent.MessageReceived)
        assertTrue(received[3] is McpEvent.SessionClosed)
    }

    @Test
    fun `InitializeRequested carries protocol version and client info`() {
        val received = mutableListOf<McpEvent>()
        val handler = McpEventHandler { received.add(it) }
        val bus = McpEventBus(listOf(handler))

        val event = McpEvent.InitializeRequested("2025-11-25", """{"name":"claude","version":"1.0"}""")
        bus.emit(event)

        assertEquals(1, received.size)
        val init = received[0] as McpEvent.InitializeRequested
        assertEquals("2025-11-25", init.protocolVersion)
        assertEquals("""{"name":"claude","version":"1.0"}""", init.clientInfo)
    }

    @Test
    fun `InitializeRequested with null protocol version and client info`() {
        val received = mutableListOf<McpEvent>()
        val handler = McpEventHandler { received.add(it) }
        val bus = McpEventBus(listOf(handler))

        val event = McpEvent.InitializeRequested(null, null)
        bus.emit(event)

        assertEquals(1, received.size)
        val init = received[0] as McpEvent.InitializeRequested
        assertNull(init.protocolVersion)
        assertNull(init.clientInfo)
    }
}
