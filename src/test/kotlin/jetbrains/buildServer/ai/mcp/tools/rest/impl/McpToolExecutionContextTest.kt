package jetbrains.buildServer.ai.mcp.tools.rest.impl

import io.mockk.mockk
import jetbrains.buildServer.users.SUser
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
            user = null,
            capturedSecurityContext = captured
        ) {
            assertSame(captured, SecurityContextHolder.getContext())
        }

        assertSame(original, SecurityContextHolder.getContext())
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `withOperationContext binds user`() = runBlocking {
        val executionContext = McpToolExecutionContext()
        val user = mockk<SUser>(relaxed = true)

        executionContext.withOperationContext(user = user) {
            assertEquals(user, executionContext.currentUser())
        }
    }

    @Test
    fun `current user is null outside operation context`() = runBlocking {
        val executionContext = McpToolExecutionContext()
        assertNull(executionContext.currentUser())
    }
}
