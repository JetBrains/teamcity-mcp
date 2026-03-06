package jetbrains.buildServer.ai.mcp.events

import jetbrains.buildServer.ai.mcp.McpSessionManager
import jetbrains.buildServer.metrics.MetricBuilder
import jetbrains.buildServer.metrics.MetricDataType
import jetbrains.buildServer.metrics.ServerMetrics
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

@Component
class ServerMetricsMcpEventHandler(
    serverMetrics: ServerMetrics,
    sessionManager: McpSessionManager
) : McpEventHandler, DisposableBean {

    private val metricBuilders = mutableListOf<MetricBuilder>()

    private val sessionsOpened = serverMetrics.metricBuilder("mcp.sessions.opened")
        .description("Number of MCP sessions opened")
        .dataType(MetricDataType.NUMBER)
        .also { metricBuilders.add(it) }
        .buildCounter()

    private val sessionsClosed = serverMetrics.metricBuilder("mcp.sessions.closed")
        .description("Number of MCP sessions closed")
        .dataType(MetricDataType.NUMBER)
        .also { metricBuilders.add(it) }
        .buildCounter()

    private val initRequests = serverMetrics.metricBuilder("mcp.sessions.init.requests")
        .description("Number of MCP session initialize requests received (including rejected)")
        .dataType(MetricDataType.NUMBER)
        .also { metricBuilders.add(it) }
        .buildCounter()

    private val messages = serverMetrics.metricBuilder("mcp.messages.received")
        .description("Number of MCP messages received on active sessions")
        .dataType(MetricDataType.NUMBER)
        .also { metricBuilders.add(it) }
        .buildCounter()

    init {
        serverMetrics.metricBuilder("mcp.sessions.active")
            .description("Number of currently active MCP sessions")
            .dataType(MetricDataType.NUMBER)
            .also { metricBuilders.add(it) }
            .buildGauge { sessionManager.getSessionCount().toDouble() }
    }

    override fun onEvent(event: McpEvent) {
        when (event) {
            is McpEvent.InitializeRequested -> initRequests.increment(1.0)
            is McpEvent.SessionStarted -> sessionsOpened.increment(1.0)
            is McpEvent.SessionClosed -> sessionsClosed.increment(1.0)
            is McpEvent.MessageReceived -> messages.increment(1.0)
        }
    }

    override fun destroy() {
        metricBuilders.forEach {
            try {
                it.unregister()
            } catch (_: Throwable) {
                // NoSuchMethodError expected: unregister() was added in 2025.03.1; ignore on older servers
            }
        }
    }
}