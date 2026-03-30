package jetbrains.buildServer.ai.mcp.events

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.TeamCityProperties
import jetbrains.buildServer.serverSide.auth.SecurityContext
import jetbrains.buildServer.serverSide.impl.fus.FusRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.teamcity.fus.domain.model.events.ai.McpServerEventsGroup.*
import org.springframework.stereotype.Component

@Component
open class FusMcpEventHandler(
    private val fusRegistry: FusRegistry,
    private val securityContext: SecurityContext
) : McpEventHandler {

    companion object {
        private val LOGGER = Logger.getInstance(FusMcpEventHandler::class.java.name)
    }

    private val fusEventsPresent by lazy { areFusEventClassesPresent() }

    override fun onEvent(event: McpEvent) {
        if (!TeamCityProperties.getBooleanOrTrue(MCP_FUS_ENABLED) || !fusEventsPresent) return

        try {
            when (event) {
                is McpEvent.InitializeRequested -> logInitializeRequested(event)
                is McpEvent.SessionStarted -> logSessionStarted(event)
                is McpEvent.MessageReceived -> logMessageReceived(event)
                is McpEvent.SessionClosed -> { /* no FUS event for session close */ }
            }
        } catch (e: Throwable) {
            LOGGER.warnAndDebugDetails("Failed to send MCP FUS event", e)
        }
    }

    protected open fun areFusEventClassesPresent(): Boolean {
        return try {
            Class.forName("org.jetbrains.teamcity.fus.domain.model.events.ai.McpServerEventsGroup.SessionStartedEvent", false, FusMcpEventHandler::class.java.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            LOGGER.debug("FUS events classes for MCP are not present, completely skipping MCP FUS event logging")
            false
        } catch (e: Throwable) {
            LOGGER.debug("FUS events for MCP are not present: ${e.message}, completely skipping MCP FUS event logging")
            false
        }
    }

    private fun logInitializeRequested(event: McpEvent.InitializeRequested) {
        val (clientName, clientVersion) = parseClientInfo(event.clientInfo)
        val fusEvent = RequestSessionEvent(
            userId = getCurrentUserIdOrEmpty(),
            requestedProtocolVersion = event.protocolVersion,
            mcpClientToolName = clientName,
            mcpClientToolVersion = clientVersion
        )
        LOGGER.debug("Sending FUS event: $fusEvent")
        fusRegistry.logEvent(fusEvent)
    }

    private fun logSessionStarted(event: McpEvent.SessionStarted) {
        val (clientName, clientVersion) = parseClientInfo(event.clientInfo)
        val fusEvent = SessionStartedEvent(
            userId = getCurrentUserIdOrEmpty(),
            mcpSessionId = event.sessionId,
            mcpClientToolName = clientName,
            mcpClientToolVersion = clientVersion
        )
        LOGGER.debug("Sending FUS event: $fusEvent")
        fusRegistry.logEvent(fusEvent)
    }

    private fun logMessageReceived(event: McpEvent.MessageReceived) {
        val fusEvent = ExistingSessionMessageReceivedEvent(
            userId = getCurrentUserIdOrEmpty(),
            mcpSessionId = event.sessionId,
            methodName = event.method
        )
        LOGGER.debug("Sending FUS event: $fusEvent")
        fusRegistry.logEvent(fusEvent)
    }

    private fun getCurrentUserIdOrEmpty() = securityContext.authorityHolder.associatedUser?.id?.toString() ?: ""

    private fun parseClientInfo(clientInfo: String?): Pair<String?, String?> {
        if (clientInfo == null) return null to null
        return try {
            val json = Json.parseToJsonElement(clientInfo).jsonObject
            val name = json["name"]?.jsonPrimitive?.content
            val version = json["version"]?.jsonPrimitive?.content
            name to version
        } catch (_: Exception) {
            null to null
        }
    }
}
