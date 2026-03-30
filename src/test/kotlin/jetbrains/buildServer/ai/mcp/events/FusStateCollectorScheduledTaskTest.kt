package jetbrains.buildServer.ai.mcp.events

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import jetbrains.buildServer.ai.mcp.MCP_FEATURE_TOGGLE
import jetbrains.buildServer.ai.mcp.SettingsService
import jetbrains.buildServer.serverSide.impl.fus.FusRegistry
import org.jetbrains.teamcity.fus.domain.model.states.ai.McpServerStateGroup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FusStateCollectorScheduledTaskTest {
    private lateinit var fusRegistry: FusRegistry
    private lateinit var collector: FusStateCollectorScheduledTask

    @BeforeEach
    fun setUp() {
        fusRegistry = mockk(relaxed = true)
        collector = object : FusStateCollectorScheduledTask(
            mockk(relaxed = true),
            mockk(relaxed = true),
            SettingsService(),
            fusRegistry
        ) {
            override fun areFusEventClassesPresent(): Boolean = true
        }

        System.clearProperty(MCP_FEATURE_TOGGLE)
        System.clearProperty(MCP_FUS_ENABLED)
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty(MCP_FEATURE_TOGGLE)
        System.clearProperty(MCP_FUS_ENABLED)
    }

    @Nested
    inner class McpSettingsTests {
        @Test
        fun `should log MCP enabled event by default`() {
            collector.run()

            verify(exactly = 1) {
                fusRegistry.logEvent(match<McpServerStateGroup.McpServerState> {
                    it.isMcpServerEnabled
                })
            }
        }

        @Test
        fun `should log MCP enabled event`() {
            System.setProperty(MCP_FEATURE_TOGGLE, "true")

            collector.run()

            verify(exactly = 1) {
                fusRegistry.logEvent(match<McpServerStateGroup.McpServerState> {
                    it.isMcpServerEnabled
                })
            }
        }

        @Test
        fun `should log MCP disabled event`() {
            System.setProperty(MCP_FEATURE_TOGGLE, "false")

            collector.run()

            verify(exactly = 1) {
                fusRegistry.logEvent(match<McpServerStateGroup.McpServerState> {
                    !it.isMcpServerEnabled
                })
            }
        }
    }

    @Nested
    inner class PropertyToggleTests {
        @Test
        fun `should not log events when it's not main node`() {
            mockkStatic(jetbrains.buildServer.serverSide.CurrentNodeInfo::class)
            every {
                jetbrains.buildServer.serverSide.CurrentNodeInfo.isMainNode()
            } returns false

            collector.run()

            verify(exactly = 0) { fusRegistry.logEvent(any()) }
            unmockkStatic(jetbrains.buildServer.serverSide.CurrentNodeInfo::class)
        }

        @Test
        fun `should not log events when FUS collection is disabled`() {
            System.setProperty(MCP_FUS_ENABLED, "false")

            collector.run()

            verify(exactly = 0) { fusRegistry.logEvent(any()) }
        }

        @Test
        fun `should not log events when MCP FUS state event classes are unavailable`() {
            val collectorWithoutFusClasses = object : FusStateCollectorScheduledTask(
                mockk(relaxed = true),
                mockk(relaxed = true),
                SettingsService(),
                fusRegistry
            ) {
                override fun areFusEventClassesPresent(): Boolean = false
            }

            collectorWithoutFusClasses.run()

            verify(exactly = 0) { fusRegistry.logEvent(any()) }
        }
    }
}