package jetbrains.buildServer.ai.mcp.tools.rest.impl

import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.serverSide.SecurityContextEx
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import javax.servlet.http.HttpServletRequest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Component
class McpToolExecutionContext(private val securityContext: SecurityContextEx) {

    suspend fun <T> withOperationContext(
        user: SUser?,
        capturedSecurityContext: SecurityContextEx.ContextState = securityContext.captureContext(),
        requestData: RequestData = RequestData(emptyMap(), emptyMap()),
        block: suspend () -> T
    ): T {
        return withContext(
            SecurityContextElement(securityContext, capturedSecurityContext)
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
 * Propagates TeamCity security context across coroutine suspensions using ThreadContextElement.
 * Saves the previous context, sets the provided one for the duration of the coroutine, then restores it.
 * Do not call [SecurityContextEx.setAuthorityHolder] from suspending code:
 * the captured context is restored after suspension.
 * Use `SecurityContextEx.runAs` methods for synchronous authority changes instead.
 */
private class SecurityContextElement(
    private val securityContext: SecurityContextEx,
    private val captured: SecurityContextEx.ContextState
) : ThreadContextElement<SecurityContextEx.ContextState>,
    AbstractCoroutineContextElement(SecurityContextElement) {

    companion object Key : CoroutineContext.Key<SecurityContextElement>

    override fun updateThreadContext(context: CoroutineContext): SecurityContextEx.ContextState {
        val previous = securityContext.captureContext()
        securityContext.restoreContext(captured)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: SecurityContextEx.ContextState) {
        securityContext.restoreContext(oldState)
    }
}
