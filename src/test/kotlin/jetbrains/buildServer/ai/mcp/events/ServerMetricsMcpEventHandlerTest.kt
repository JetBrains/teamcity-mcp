package jetbrains.buildServer.ai.mcp.events

import io.mockk.*
import jetbrains.buildServer.ai.mcp.McpSessionManager
import jetbrains.buildServer.metrics.Counter
import jetbrains.buildServer.metrics.MetricBuilder
import jetbrains.buildServer.metrics.ServerMetrics
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ServerMetricsMcpEventHandlerTest {

    private lateinit var serverMetrics: ServerMetrics
    private lateinit var sessionManager: McpSessionManager
    private lateinit var sessionsOpenedCounter: Counter
    private lateinit var sessionsClosedCounter: Counter
    private lateinit var initRequestsCounter: Counter
    private lateinit var messagesCounter: Counter
    private lateinit var builders: MutableMap<String, MetricBuilder>
    private lateinit var handler: ServerMetricsMcpEventHandler

    @BeforeEach
    fun setUp() {
        serverMetrics = mockk()
        sessionManager = mockk()
        sessionsOpenedCounter = mockk(relaxed = true)
        sessionsClosedCounter = mockk(relaxed = true)
        initRequestsCounter = mockk(relaxed = true)
        messagesCounter = mockk(relaxed = true)
        builders = mutableMapOf()

        fun mockBuilder(name: String, counter: Counter? = null): MetricBuilder {
            val builder = mockk<MetricBuilder>(relaxed = true)
            every { builder.description(any()) } returns builder
            every { builder.dataType(any()) } returns builder
            if (counter != null) {
                every { builder.buildCounter() } returns counter
            }
            every { builder.buildGauge(any()) } just Runs
            every { builder.unregister() } just Runs
            every { serverMetrics.metricBuilder(name) } returns builder
            builders[name] = builder
            return builder
        }

        mockBuilder("mcp.sessions.opened", sessionsOpenedCounter)
        mockBuilder("mcp.sessions.closed", sessionsClosedCounter)
        mockBuilder("mcp.sessions.init.requests", initRequestsCounter)
        mockBuilder("mcp.messages.received", messagesCounter)
        mockBuilder("mcp.sessions.active")

        handler = ServerMetricsMcpEventHandler(serverMetrics, sessionManager)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `SessionStarted increments sessions opened counter`() {
        handler.onEvent(McpEvent.SessionStarted("s1", "client"))

        verify(exactly = 1) { sessionsOpenedCounter.increment(1.0) }
    }

    @Test
    fun `SessionStarted recovery increments sessions opened counter`() {
        handler.onEvent(McpEvent.SessionStarted("s1", null))

        verify(exactly = 1) { sessionsOpenedCounter.increment(1.0) }
    }

    @Test
    fun `SessionClosed increments sessions closed counter`() {
        handler.onEvent(McpEvent.SessionClosed("s1", "client request"))

        verify(exactly = 1) { sessionsClosedCounter.increment(1.0) }
    }

    @Test
    fun `InitializeRequested increments init requests counter`() {
        handler.onEvent(McpEvent.InitializeRequested("2025-11-25", """{"name":"test"}"""))

        verify(exactly = 1) { initRequestsCounter.increment(1.0) }
    }

    @Test
    fun `MessageReceived increments messages counter`() {
        handler.onEvent(McpEvent.MessageReceived("s1", "tools/list"))

        verify(exactly = 1) { messagesCounter.increment(1.0) }
    }

    @Test
    fun `multiple events increment counters independently`() {
        handler.onEvent(McpEvent.InitializeRequested("2025-11-25", null))
        handler.onEvent(McpEvent.SessionStarted("s1", null))
        handler.onEvent(McpEvent.MessageReceived("s1", "tools/list"))
        handler.onEvent(McpEvent.MessageReceived("s1", "tools/call"))
        handler.onEvent(McpEvent.SessionClosed("s1", "done"))

        verify(exactly = 1) { initRequestsCounter.increment(1.0) }
        verify(exactly = 1) { sessionsOpenedCounter.increment(1.0) }
        verify(exactly = 2) { messagesCounter.increment(1.0) }
        verify(exactly = 1) { sessionsClosedCounter.increment(1.0) }
    }

    @Test
    fun `active sessions gauge is registered with session manager`() {
        val gaugeBuilder = mockk<MetricBuilder>()
        every { gaugeBuilder.description(any()) } returns gaugeBuilder
        every { gaugeBuilder.dataType(any()) } returns gaugeBuilder
        every { gaugeBuilder.unregister() } just Runs

        val capturedProvider = slot<jetbrains.buildServer.metrics.GaugeValueProvider>()
        every { gaugeBuilder.buildGauge(capture(capturedProvider)) } just Runs
        every { serverMetrics.metricBuilder("mcp.sessions.active") } returns gaugeBuilder

        ServerMetricsMcpEventHandler(serverMetrics, sessionManager)

        every { sessionManager.getSessionCount() } returns 5
        val value = capturedProvider.captured.get()
        assert(value == 5.0) { "Expected 5.0, got $value" }
    }

    @Test
    fun `destroy unregisters all metrics`() {
        handler.destroy()

        for ((name, builder) in builders) {
            verify(exactly = 1) { builder.unregister() }
        }
    }

    @Test
    fun `destroy tolerates NoSuchMethodError when unregister is unavailable`() {
        for ((_, builder) in builders) {
            every { builder.unregister() } throws NoSuchMethodError("unregister")
        }

        handler.destroy() // should not throw
    }
}
