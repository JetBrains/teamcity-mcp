package jetbrains.buildServer.ai.mcp.events

import com.intellij.openapi.diagnostic.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Dispatches [McpEvent]s to all registered [McpEventHandler]s.
 */
@Component
class McpEventBus(@Autowired(required = false) eventHandlers: List<McpEventHandler>?) {

    companion object {
        private val LOGGER = Logger.getInstance(McpEventBus::class.java.name)
    }

    private val handlers: List<McpEventHandler> = eventHandlers ?: emptyList()

    fun emit(event: McpEvent) {
        for (handler in handlers) {
            try {
                handler.onEvent(event)
            } catch (e: Throwable) {
                LOGGER.warn("McpEventHandler ${handler::class.simpleName} threw on ${event::class.simpleName}", e)
            }
        }
    }
}
