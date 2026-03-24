package jetbrains.buildServer.ai.mcp.events

import io.mockk.*
import jetbrains.buildServer.serverSide.auth.SecurityContext
import jetbrains.buildServer.serverSide.impl.fus.FusRegistry
import jetbrains.buildServer.users.SUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.jetbrains.teamcity.fus.domain.model.events.ai.McpServerEventsGroup.*

class FusMcpEventHandlerTest {

    private lateinit var fusRegistry: FusRegistry
    private lateinit var handler: FusMcpEventHandler
    private lateinit var securityContext: SecurityContext

    @BeforeEach
    fun setUp() {
        fusRegistry = mockk(relaxed = true)
        securityContext = mockk(relaxed = true)
        handler = FusMcpEventHandler(fusRegistry, securityContext)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun setSecurityUser(userId: Long) {
        // Deep-stub the security context chain: authorityHolder.associatedUser?.id
        val user = mockk<SUser>()
        every { user.id } returns userId
        val authorityHolder = mockk<jetbrains.buildServer.serverSide.auth.AuthorityHolder>()
        every { authorityHolder.associatedUser } returns user
        every { securityContext.authorityHolder } returns authorityHolder
    }

    @Nested
    inner class InitializeRequestedTests {

        @Test
        fun `maps InitializeRequested to RequestSessionEvent with parsed client info`() {
            setSecurityUser(42)
            val clientInfo = """{"name":"claude-code","version":"2.1.50"}"""

            handler.onEvent(McpEvent.InitializeRequested("2025-11-25", clientInfo))

            verify(exactly = 1) {
                fusRegistry.logEvent(match<RequestSessionEvent> {
                    it.userId == "42" &&
                    it.requestedProtocolVersion == "2025-11-25" &&
                    it.mcpClientToolName == "claude-code" &&
                    it.mcpClientToolVersion == "2.1.50"
                })
            }
        }

        @Test
        fun `handles null client info`() {
            setSecurityUser(7)

            handler.onEvent(McpEvent.InitializeRequested("2025-11-25", null))

            verify(exactly = 1) {
                fusRegistry.logEvent(match<RequestSessionEvent> {
                    it.userId == "7" &&
                    it.requestedProtocolVersion == "2025-11-25" &&
                    it.mcpClientToolName == null &&
                    it.mcpClientToolVersion == null
                })
            }
        }

        @Test
        fun `handles null protocol version`() {
            setSecurityUser(1)

            handler.onEvent(McpEvent.InitializeRequested(null, """{"name":"test","version":"1.0"}"""))

            verify(exactly = 1) {
                fusRegistry.logEvent(match<RequestSessionEvent> {
                    it.requestedProtocolVersion == null &&
                    it.mcpClientToolName == "test"
                })
            }
        }

        @Test
        fun `handles malformed client info JSON gracefully`() {
            setSecurityUser(1)

            handler.onEvent(McpEvent.InitializeRequested("2025-11-25", "not-json"))

            verify(exactly = 1) {
                fusRegistry.logEvent(match<RequestSessionEvent> {
                    it.mcpClientToolName == null &&
                    it.mcpClientToolVersion == null
                })
            }
        }

        @Test
        fun `handles client info with missing name field`() {
            setSecurityUser(1)

            handler.onEvent(McpEvent.InitializeRequested("2025-11-25", """{"version":"1.0"}"""))

            verify(exactly = 1) {
                fusRegistry.logEvent(match<RequestSessionEvent> {
                    it.mcpClientToolName == null &&
                    it.mcpClientToolVersion == "1.0"
                })
            }
        }
    }

    @Nested
    inner class SessionStartedTests {

        @Test
        fun `maps SessionStarted to SessionStartedEvent with parsed client info`() {
            setSecurityUser(42)
            val clientInfo = """{"name":"cursor","version":"0.50.1"}"""

            handler.onEvent(McpEvent.SessionStarted("session-123", clientInfo))

            verify(exactly = 1) {
                fusRegistry.logEvent(match<SessionStartedEvent> {
                    it.userId == "42" &&
                    it.mcpSessionId == "session-123" &&
                    it.mcpClientToolName == "cursor" &&
                    it.mcpClientToolVersion == "0.50.1"
                })
            }
        }

        @Test
        fun `handles null client info`() {
            setSecurityUser(1)

            handler.onEvent(McpEvent.SessionStarted("s1", null))

            verify(exactly = 1) {
                fusRegistry.logEvent(match<SessionStartedEvent> {
                    it.mcpSessionId == "s1" &&
                    it.mcpClientToolName == null &&
                    it.mcpClientToolVersion == null
                })
            }
        }
    }

    @Nested
    inner class MessageReceivedTests {

        @Test
        fun `maps MessageReceived to ExistingSessionMessageReceivedEvent`() {
            setSecurityUser(42)

            handler.onEvent(McpEvent.MessageReceived("session-123", "tools/list"))

            verify(exactly = 1) {
                fusRegistry.logEvent(match<ExistingSessionMessageReceivedEvent> {
                    it.userId == "42" &&
                    it.mcpSessionId == "session-123" &&
                    it.methodName == "tools/list"
                })
            }
        }

        @Test
        fun `handles null method name`() {
            setSecurityUser(1)

            handler.onEvent(McpEvent.MessageReceived("s1", null))

            verify(exactly = 1) {
                fusRegistry.logEvent(match<ExistingSessionMessageReceivedEvent> {
                    it.mcpSessionId == "s1" &&
                    it.methodName == null
                })
            }
        }
    }

    @Nested
    inner class SessionClosedTests {

        @Test
        fun `SessionClosed does not emit any FUS event`() {
            handler.onEvent(McpEvent.SessionClosed("s1", "client request"))

            verify(exactly = 0) { fusRegistry.logEvent(any()) }
        }
    }

    @Nested
    inner class UserIdResolutionTests {

        @Test
        fun `uses empty string when no security context is set`() {
            // No setSecurityUser call

            handler.onEvent(McpEvent.InitializeRequested("2025-11-25", null))

            verify(exactly = 1) {
                fusRegistry.logEvent(match<RequestSessionEvent> {
                    it.userId == ""
                })
            }
        }

        //todo: what we want to test here?
//        @Test
//        fun `uses empty string when authentication principal is not SUser`() {
//            val auth = UsernamePasswordAuthenticationToken("string-principal", null)
//            SecurityContextHolder.getContext().authentication = auth
//
//            handler.onEvent(McpEvent.MessageReceived("s1", "ping"))
//
//            verify(exactly = 1) {
//                fusRegistry.logEvent(match<ExistingSessionMessageReceivedEvent> {
//                    it.userId == ""
//                })
//            }
//        }
    }

    @Nested
    inner class ErrorResilienceTests {

        @Test
        fun `does not throw when FusRegistry throws`() {
            setSecurityUser(1)
            every { fusRegistry.logEvent(any()) } throws RuntimeException("FUS failure")

            // Should not throw
            handler.onEvent(McpEvent.InitializeRequested("2025-11-25", null))
        }
    }

    @Nested
    inner class PropertyToggleTests {

        @Test
        fun `does not log events when FUS collection is disabled`() {
            setSecurityUser(1)

            mockkStatic(jetbrains.buildServer.serverSide.TeamCityProperties::class)
            every {
                jetbrains.buildServer.serverSide.TeamCityProperties.getBooleanOrTrue("teamcity.ai.mcp.fus.enabled")
            } returns false

            val toggleHandler = FusMcpEventHandler(fusRegistry, securityContext)

            toggleHandler.onEvent(McpEvent.InitializeRequested("2025-11-25", null))
            toggleHandler.onEvent(McpEvent.SessionStarted("s1", null))
            toggleHandler.onEvent(McpEvent.MessageReceived("s1", "ping"))

            verify(exactly = 0) { fusRegistry.logEvent(any()) }

            unmockkStatic(jetbrains.buildServer.serverSide.TeamCityProperties::class)
        }
    }
}
