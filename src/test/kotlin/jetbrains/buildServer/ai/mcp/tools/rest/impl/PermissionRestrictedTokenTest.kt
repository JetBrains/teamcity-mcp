package jetbrains.buildServer.ai.mcp.tools.rest.impl

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import jetbrains.buildServer.ai.mcp.McpProtocolVersion
import jetbrains.buildServer.ai.mcp.McpServerConfigurator
import jetbrains.buildServer.ai.mcp.McpSessionManager
import jetbrains.buildServer.ai.mcp.McpStreamableHttpController
import jetbrains.buildServer.ai.mcp.McpStreamableHttpTransport
import jetbrains.buildServer.ai.mcp.SettingsService
import jetbrains.buildServer.ai.mcp.events.McpEventBus
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.controllers.fakes.FakeHttpRequestsFactory
import jetbrains.buildServer.controllers.fakes.FakeHttpServletRequest
import jetbrains.buildServer.controllers.fakes.FakeHttpServletResponse
import jetbrains.buildServer.serverSide.SecurityContextEx
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.web.util.SessionUser
import jetbrains.spring.web.UrlMapping
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Verifies that a permission-restricted token user is correctly propagated
 * through the entire MCP pipeline without being unwrapped to the underlying admin.
 *
 * When a user authenticates via a token with restricted scope, Spring Security
 * creates a RestrictedUserImpl wrapping the BaseUser. Calling .associatedUser
 * on RestrictedUserImpl returns the unwrapped full-privilege BaseUser.
 *
 * This test suite verifies two critical layers:
 * 1. **Controller level**: McpStreamableHttpController must pass the restricted user
 *    (authorityHolder itself) to withOperationContext — NOT authorityHolder.associatedUser.
 * 2. **RestApiClient level**: RestApiClientImpl must set the restricted user as
 *    SessionUser on the internal HTTP request, preserving it through coroutine context.
 */
class PermissionRestrictedTokenTest {

    private val adminUser = mockk<SUser>(relaxed = true) {
        every { associatedUser } returns this@mockk // admin's associatedUser is itself
    }
    private val restrictedUser = mockk<SUser>(relaxed = true) {
        every { associatedUser } returns adminUser // restricted token unwraps to admin
    }

    // =========================================================================
    // Layer 1: McpStreamableHttpController — captures user from SecurityContext
    // =========================================================================

    @Nested
    inner class `controller captures restricted user from security context` {

        private val settingsService = mockk<SettingsService> {
            every { isMcpServerEnabled() } returns true
        }
        private val sessionManager = mockk<McpSessionManager>(relaxed = true)
        private val serverConfigurator = mockk<McpServerConfigurator>()
        private val eventBus = mockk<McpEventBus>(relaxed = true)
        private val securityContext = mockk<SecurityContextEx>(relaxed = true)

        /**
         * Creates an [McpStreamableHttpController] wired with a spied [McpToolExecutionContext]
         * so we can verify which user is passed to [McpToolExecutionContext.withOperationContext].
         *
         * Caller is responsible for calling [McpStreamableHttpController.destroy] when done.
         */
        private fun createControllerWithSpiedContext(
            spiedContext: McpToolExecutionContext = spyk(McpToolExecutionContext())
        ): Pair<McpStreamableHttpController, McpToolExecutionContext> {
            val controller = McpStreamableHttpController(
                settingsService = settingsService,
                sessionManager = sessionManager,
                serverConfigurator = serverConfigurator,
                eventBus = eventBus,
                toolExecutionContext = spiedContext,
                securityContext = securityContext
            )
            return controller to spiedContext
        }

        private fun postNotification(controller: McpStreamableHttpController) {
            val transport = mockk<McpStreamableHttpTransport>(relaxed = true)
            every { sessionManager.getSession("s1") } returns transport

            controller.handlePost(
                protocolVersion = McpProtocolVersion.VERSION_2025_11_25,
                sessionId = "s1",
                accept = "application/json",
                contentType = "application/json",
                body = """{"jsonrpc":"2.0","method":"notifications/test"}""",
                servletRequest = FakeHttpServletRequest()
            )
        }

        @Test
        fun `restricted user from authorityHolder is passed to execution context — not unwrapped admin`() {
            every { securityContext.authorityHolder } returns restrictedUser

            val (httpController, spiedContext) = createControllerWithSpiedContext()

            try {
                postNotification(httpController)

                // The controller must pass restrictedUser, NOT adminUser.
                // If .associatedUser is called, adminUser would be passed instead — that's the bug.
                coVerify(timeout = 2_000) {
                    spiedContext.withOperationContext(
                        user = restrictedUser,
                        capturedSecurityContext = any(),
                        block = any()
                    )
                }
            } finally {
                httpController.destroy()
            }
        }

        @Test
        fun `admin user from authorityHolder is passed as-is when no token restriction`() {
            every { securityContext.authorityHolder } returns adminUser

            val (httpController, spiedContext) = createControllerWithSpiedContext()

            try {
                postNotification(httpController)

                coVerify(timeout = 2_000) {
                    spiedContext.withOperationContext(
                        user = adminUser,
                        capturedSecurityContext = any(),
                        block = any()
                    )
                }
            } finally {
                httpController.destroy()
            }
        }
    }

    // =========================================================================
    // Layer 2: RestApiClientImpl — propagates user to internal REST request
    // =========================================================================

    @Nested
    inner class `rest client propagates restricted user to internal request` {

        private val fakeHttpRequestsFactory = mockk<FakeHttpRequestsFactory>()
        private val urlMapping = mockk<UrlMapping>()
        private val executionContext = McpToolExecutionContext()
        private val client = RestApiClientImpl(executionContext, fakeHttpRequestsFactory, urlMapping)

        /**
         * Sets up a mock controller that captures the [SessionUser] from each incoming request.
         */
        private fun setupCapturingController(): MutableList<SUser?> {
            val capturedUsers = mutableListOf<SUser?>()
            val fakeRequest = FakeHttpServletRequest()
            every { fakeHttpRequestsFactory.get(any(), any()) } returns fakeRequest

            val controller = mockk<BaseController>()
            every { urlMapping.handlerMap } returns mapOf("/app/rest/**" to controller)
            every { controller.handleRequestInternal(any(), any()) } answers {
                capturedUsers.add(SessionUser.getUser(firstArg<javax.servlet.http.HttpServletRequest>()))
                secondArg<FakeHttpServletResponse>().status = 200
                null
            }

            return capturedUsers
        }

        @Test
        fun `admin user in context reaches controller as admin`() {
            val capturedUsers = setupCapturingController()

            runBlocking {
                executionContext.withOperationContext(user = adminUser) {
                    client.get("/app/rest/projects", "fields=project(id)")
                }
            }

            assertEquals(1, capturedUsers.size)
            assertSame(adminUser, capturedUsers[0],
                "Controller must receive the admin user when admin is in context")
        }

        @Test
        fun `restricted user in context reaches controller as restricted — not as underlying admin`() {
            val capturedUsers = setupCapturingController()

            runBlocking {
                executionContext.withOperationContext(user = restrictedUser) {
                    client.get("/app/rest/projects", "fields=project(id)")
                }
            }

            assertEquals(1, capturedUsers.size)
            assertSame(restrictedUser, capturedUsers[0],
                "Controller must receive the restricted user, not the unwrapped admin")
            assertNotSame(adminUser, capturedUsers[0],
                "The underlying admin must NOT reach the controller")
        }

        @Test
        fun `restricted user is preserved across multiple sequential REST calls`() {
            val capturedUsers = setupCapturingController()

            runBlocking {
                executionContext.withOperationContext(user = restrictedUser) {
                    client.get("/app/rest/projects", "fields=project(id)")
                    client.get("/app/rest/buildTypes", "fields=buildType(id)")
                }
            }

            assertEquals(2, capturedUsers.size)
            assertSame(restrictedUser, capturedUsers[0],
                "First REST call must use restricted user")
            assertSame(restrictedUser, capturedUsers[1],
                "Second REST call must still use restricted user")
        }

        @Test
        fun `restricted user is preserved for POST requests`() {
            val capturedUsers = setupCapturingController()

            runBlocking {
                executionContext.withOperationContext(user = restrictedUser) {
                    client.post("/app/rest/buildQueue", "", """{"buildType":{"id":"bt1"}}""")
                }
            }

            assertEquals(1, capturedUsers.size)
            assertSame(restrictedUser, capturedUsers[0],
                "POST request must also use restricted user, not admin")
        }

        @Test
        fun `security context is propagated alongside restricted user`() {
            val mockAuthentication = mockk<Authentication>(relaxed = true)
            val mockSecurityContext = mockk<SecurityContext>()
            every { mockSecurityContext.authentication } returns mockAuthentication

            var authOnRequest: Authentication? = null
            val fakeRequest = FakeHttpServletRequest()
            every { fakeHttpRequestsFactory.get(any(), any()) } returns fakeRequest

            val controller = mockk<BaseController>()
            every { urlMapping.handlerMap } returns mapOf("/app/rest/**" to controller)
            every { controller.handleRequestInternal(any(), any()) } answers {
                authOnRequest = SecurityContextHolder.getContext().authentication
                secondArg<FakeHttpServletResponse>().status = 200
                null
            }

            runBlocking {
                executionContext.withOperationContext(
                    user = restrictedUser,
                    capturedSecurityContext = mockSecurityContext
                ) {
                    client.get("/app/rest/projects", "")
                }
            }

            assertSame(mockAuthentication, authOnRequest,
                "SecurityContext authentication must be propagated to the controller thread")
        }

        @Test
        fun `different users in sequential operations each reach controller correctly`() {
            val capturedUsers = setupCapturingController()

            runBlocking {
                executionContext.withOperationContext(user = adminUser) {
                    client.get("/app/rest/projects", "fields=project(id)")
                }
                executionContext.withOperationContext(user = restrictedUser) {
                    client.get("/app/rest/projects", "fields=project(id)")
                }
            }

            assertEquals(2, capturedUsers.size)
            assertSame(adminUser, capturedUsers[0],
                "First call with admin user must reach controller as admin")
            assertSame(restrictedUser, capturedUsers[1],
                "Second call with restricted user must reach controller as restricted")
            assertNotSame(capturedUsers[0], capturedUsers[1],
                "Admin and restricted users must be distinct on the controller side")
        }
    }
}
