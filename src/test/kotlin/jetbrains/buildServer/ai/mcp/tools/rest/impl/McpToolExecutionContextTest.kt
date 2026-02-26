package jetbrains.buildServer.ai.mcp.tools.rest.impl

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class McpToolExecutionContextTest {

    @Test
    fun `withOperationContext propagates and restores security context`() = runBlocking {
        val executionContext = McpToolExecutionContext()

        val original = SecurityContextHolder.createEmptyContext().also {
            it.authentication = mockk<Authentication>(relaxed = true)
        }
        SecurityContextHolder.setContext(original)

        val captured = SecurityContextHolder.createEmptyContext().also {
            it.authentication = mockk<Authentication>(relaxed = true)
        }
        val request = mockk<HttpServletRequest>()
        val response = mockk<HttpServletResponse>()

        executionContext.withOperationContext(
            servletRequest = request,
            servletResponse = response,
            capturedSecurityContext = captured
        ) {
            assertSame(captured, SecurityContextHolder.getContext())
        }

        assertSame(original, SecurityContextHolder.getContext())
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `withOperationContext binds servlet forwarding context`() = runBlocking {
        val executionContext = McpToolExecutionContext()
        val request = mockk<HttpServletRequest>()
        val response = mockk<HttpServletResponse>()

        executionContext.withOperationContext(
            servletRequest = request,
            servletResponse = response
        ) {
            val ctx = executionContext.currentServletForwardContext()
            assertSame(request, ctx!!.request)
            assertSame(response, ctx.response)
        }
    }

    @Test
    fun `current servlet forwarding context is absent outside operation context`() = runBlocking {
        val executionContext = McpToolExecutionContext()
        assertNull(executionContext.currentServletForwardContext())
    }
}
