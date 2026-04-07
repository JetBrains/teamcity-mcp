package jetbrains.buildServer.ai.mcp.events

import com.intellij.openapi.diagnostic.Logger

object McpFusUtils {

    private val LOGGER = Logger.getInstance(McpFusUtils::class.java.name)

    const val MCP_FUS_ENABLED = "teamcity.ai.mcp.fus.enabled"

    fun areFusEventClassesPresent(fullClassNameToFind: String): Boolean {
        return try {
            Class.forName(fullClassNameToFind, false, McpFusUtils::class.java.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            LOGGER.debug("FUS events classes `$fullClassNameToFind` for MCP are not present, completely skipping MCP FUS event logging")
            false
        } catch (e: Throwable) {
            LOGGER.debug("FUS events for MCP are not present: ${e.message}, completely skipping MCP FUS event logging")
            false
        }
    }
}