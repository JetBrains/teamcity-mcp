package jetbrains.buildServer.ai.mcp.tools.rest.impl

import jetbrains.buildServer.users.SUser
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import javax.servlet.http.HttpServletRequest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Component
class McpToolExecutionContext {

    suspend fun <T> withOperationContext(
        user: SUser?,
        capturedSecurityContext: SecurityContext = SecurityContextHolder.getContext(),
        requestData: RequestData = RequestData(emptyMap(), emptyMap()),
        block: suspend () -> T
    ): T {
        return withContext(
            SecurityContextElement(capturedSecurityContext)
                    + UserElement(user)
                    + RequestDataElement(requestData)
        ) {
            block()
        }
    }

    suspend fun currentUser(): SUser? {
        return currentCoroutineContext()[UserElement]?.user
    }

    suspend fun currentRequestData(): RequestData? {
        return currentCoroutineContext()[RequestDataElement]?.requestData
    }

    suspend fun applyRequestData(request: HttpServletRequest) {
        val data = currentRequestData() ?: return
        data.sessionAttributes.forEach { (key, value) ->
            request.session.setAttribute(key, value)
        }
        data.requestAttributes.forEach { (key, value) ->
            request.setAttribute(key, value)
        }
    }

}

private class UserElement(
    val user: SUser?
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<UserElement>
}

private class RequestDataElement(
    val requestData: RequestData
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestDataElement>
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
