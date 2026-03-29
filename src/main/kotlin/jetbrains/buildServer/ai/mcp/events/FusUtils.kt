package jetbrains.buildServer.ai.mcp.events

import com.intellij.openapi.diagnostic.Logger

object FusUtils {
    private val LOGGER = Logger.getInstance(FusUtils::class.java.name)
    const val MCP_FUS_ENABLED = "teamcity.ai.mcp.fus.enabled"

    fun areFusEventClassesPresent(): Boolean {
        return try {
            Class.forName("org.jetbrains.teamcity.fus.domain.model.events.ai.McpServerEventsGroup.SessionStartedEvent", false, FusUtils::class.java.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            LOGGER.debug("FUS events classes for MCP are not present, completely skipping MCP FUS event logging")
            false
        } catch (e: Throwable) {
            LOGGER.debug("FUS events for MCP are not present: ${e.message}, completely skipping MCP FUS event logging")
            false
        }
    }
}