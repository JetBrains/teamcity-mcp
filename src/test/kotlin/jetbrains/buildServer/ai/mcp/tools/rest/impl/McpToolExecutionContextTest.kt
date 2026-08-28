package jetbrains.buildServer.ai.mcp.tools.rest.impl

import io.mockk.every
import io.mockk.mockk
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.serverSide.SecurityContextEx
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

class McpToolExecutionContextTest {

    private val initialContext = mockk<SecurityContextEx.ContextState>()
    private val securityContext = mockk<SecurityContextEx>(relaxed = true) {
        every { captureContext() } returns initialContext
    }
    private val executionContext = McpToolExecutionContext(securityContext)

    @Test
    fun `withOperationContext propagates and restores security context`() = runBlocking {
        val captured = mockk<SecurityContextEx.ContextState>()
        val activeContext = AtomicReference(initialContext)
        every { securityContext.restoreContext(any()) } answers { activeContext.set(firstArg()) }

        executionContext.withOperationContext(
            user = null,
            capturedSecurityContext = captured
        ) {
            assertSame(captured, activeContext.get())
        }

        assertSame(initialContext, activeContext.get())
    }

    @Test
    fun `withOperationContext binds user`() = runBlocking {
        val user = mockk<SUser>(relaxed = true)

        executionContext.withOperationContext(user = user) {
            assertEquals(user, executionContext.currentUser())
        }
    }

    @Test
    fun `current user is null outside operation context`() = runBlocking {
        assertNull(executionContext.currentUser())
    }
}
