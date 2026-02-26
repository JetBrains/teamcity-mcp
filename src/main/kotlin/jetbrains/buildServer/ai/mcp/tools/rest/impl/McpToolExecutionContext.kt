package jetbrains.buildServer.ai.mcp.tools.rest.impl

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class ServletForwardContext(
    val request: HttpServletRequest,
    val response: HttpServletResponse
)

@Component
class McpToolExecutionContext {

    suspend fun <T> withServletForwardContext(
        request: HttpServletRequest,
        response: HttpServletResponse,
        block: suspend () -> T
    ): T {
        return withContext(ServletForwardContextElement(ServletForwardContext(request, response))) {
            block()
        }
    }

    suspend fun <T> withOperationContext(
        servletRequest: HttpServletRequest,
        servletResponse: HttpServletResponse,
        capturedSecurityContext: SecurityContext = SecurityContextHolder.getContext(),
        block: suspend () -> T
    ): T {
        return withContext(
            SecurityContextElement(capturedSecurityContext) +
                    ServletForwardContextElement(ServletForwardContext(servletRequest, servletResponse))
        ) {
            block()
        }
    }

    suspend fun currentServletForwardContext(): ServletForwardContext? {
        return currentCoroutineContext()[ServletForwardContextElement]?.forwardContext
    }
}

private class ServletForwardContextElement(
    val forwardContext: ServletForwardContext
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ServletForwardContextElement>
}


/**
 * Propagates Spring SecurityContext across coroutine suspensions using ThreadContextElement.
 * Saves the previous context, sets the provided one for the duration of the coroutine, then restores it.
 */
private class SecurityContextElement(private val captured: SecurityContext?) : ThreadContextElement<SecurityContext?>,
    AbstractCoroutineContextElement(SecurityContextElement) {

    companion object Key : CoroutineContext.Key<SecurityContextElement>

    override fun updateThreadContext(context: CoroutineContext): SecurityContext? {
        val previous = SecurityContextHolder.getContext()
        if (captured == null) SecurityContextHolder.clearContext() else SecurityContextHolder.setContext(captured)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: SecurityContext?) {
        if (oldState == null) SecurityContextHolder.clearContext() else SecurityContextHolder.setContext(oldState)
    }
}
