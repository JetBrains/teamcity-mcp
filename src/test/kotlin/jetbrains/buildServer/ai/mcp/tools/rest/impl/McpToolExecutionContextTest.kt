package jetbrains.buildServer.ai.mcp.tools.rest.impl

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder

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

        executionContext.withOperationContext(
            authorizationHeader = null,
            capturedSecurityContext = captured
        ) {
            assertSame(captured, SecurityContextHolder.getContext())
        }

        assertSame(original, SecurityContextHolder.getContext())
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `withOperationContext binds authorization header`() = runBlocking {
        val executionContext = McpToolExecutionContext()

        executionContext.withOperationContext(
            authorizationHeader = "Bearer test-token"
        ) {
            assertEquals("Bearer test-token", executionContext.currentAuthorizationHeader())
        }
    }

    @Test
    fun `current authorization header is null outside operation context`() = runBlocking {
        val executionContext = McpToolExecutionContext()
        assertNull(executionContext.currentAuthorizationHeader())
    }
}
