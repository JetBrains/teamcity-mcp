package jetbrains.buildServer.ai.mcp.tools.rest.impl

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Component
class McpToolExecutionContext {

    suspend fun <T> withOperationContext(
        authorizationHeader: String?,
        capturedSecurityContext: SecurityContext = SecurityContextHolder.getContext(),
        block: suspend () -> T
    ): T {
        return withContext(
            SecurityContextElement(capturedSecurityContext) + AuthorizationHeaderElement(authorizationHeader)
        ) {
            block()
        }
    }

    suspend fun currentAuthorizationHeader(): String? {
        return currentCoroutineContext()[AuthorizationHeaderElement]?.header
    }
}

private class AuthorizationHeaderElement(
    val header: String?
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AuthorizationHeaderElement>
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
