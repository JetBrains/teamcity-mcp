package jetbrains.buildServer.ai.mcp

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import jetbrains.buildServer.ai.mcp.resources.IntroduceYourselfResource
import jetbrains.buildServer.ai.mcp.resources.rest.PipelineBraveGuideResource
import jetbrains.buildServer.ai.mcp.resources.rest.PipelineReadOnlyGuideResource
import jetbrains.buildServer.ai.mcp.tools.IntroduceYourselfTool
import jetbrains.buildServer.ai.mcp.tools.pipeline.PipelineGetTool
import jetbrains.buildServer.ai.mcp.tools.pipeline.PipelinePostTool
import jetbrains.buildServer.ai.mcp.tools.rest.BuildLogTool
import jetbrains.buildServer.ai.mcp.tools.rest.RestDeleteTool
import jetbrains.buildServer.ai.mcp.tools.rest.RestGetTool
import jetbrains.buildServer.ai.mcp.tools.rest.RestPostTool
import jetbrains.buildServer.ai.mcp.tools.rest.RestPutTool
import jetbrains.buildServer.serverSide.TeamCityProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsServiceTest {
    private val settingsService = SettingsService()

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `pipeline is enabled by default and brave mode is disabled by default`() {
        mockkStatic(TeamCityProperties::class)
        every { TeamCityProperties.getBooleanOrTrue(MCP_PIPELINE_TOGGLE) } returns true
        every { TeamCityProperties.getBoolean(MCP_BRAVE_MODE_TOGGLE) } returns false

        assertTrue(settingsService.isPipelineEnabled())
        assertFalse(settingsService.isBraveModeEnabled())
    }

    @Test
    fun `default tools exclude pipeline tools when pipeline support is disabled`() {
        mockkStatic(TeamCityProperties::class)
        every { TeamCityProperties.getPropertyOrNull(MCP_TOOLS_ENABLED) } returns null
        every { TeamCityProperties.getBooleanOrTrue(MCP_PIPELINE_TOGGLE) } returns false
        every { TeamCityProperties.getBoolean(MCP_BRAVE_MODE_TOGGLE) } returns false

        val enabled = settingsService.getEnabledToolNames()

        assertEquals(setOf(RestGetTool.NAME, RestPostTool.NAME, BuildLogTool.NAME), enabled)
    }

    @Test
    fun `default tools include only pipeline get when pipeline is enabled and brave mode is off`() {
        mockkStatic(TeamCityProperties::class)
        every { TeamCityProperties.getPropertyOrNull(MCP_TOOLS_ENABLED) } returns null
        every { TeamCityProperties.getBooleanOrTrue(MCP_PIPELINE_TOGGLE) } returns true
        every { TeamCityProperties.getBoolean(MCP_BRAVE_MODE_TOGGLE) } returns false

        val enabled = settingsService.getEnabledToolNames()

        assertTrue(enabled.contains(PipelineGetTool.NAME))
        assertFalse(enabled.contains(PipelinePostTool.NAME))
    }

    @Test
    fun `default tools include both pipeline tools when pipeline and brave mode are enabled`() {
        mockkStatic(TeamCityProperties::class)
        every { TeamCityProperties.getPropertyOrNull(MCP_TOOLS_ENABLED) } returns null
        every { TeamCityProperties.getBooleanOrTrue(MCP_PIPELINE_TOGGLE) } returns true
        every { TeamCityProperties.getBoolean(MCP_BRAVE_MODE_TOGGLE) } returns true

        val enabled = settingsService.getEnabledToolNames()

        assertTrue(enabled.contains(PipelineGetTool.NAME))
        assertTrue(enabled.contains(PipelinePostTool.NAME))
    }

    @Test
    fun `configured tool list is returned as is`() {
        mockkStatic(TeamCityProperties::class)
        every { TeamCityProperties.getPropertyOrNull(MCP_TOOLS_ENABLED) } returns
            "${RestGetTool.NAME},${PipelineGetTool.NAME},${PipelinePostTool.NAME}"
        every { TeamCityProperties.getBooleanOrTrue(MCP_PIPELINE_TOGGLE) } returns true
        every { TeamCityProperties.getBoolean(MCP_BRAVE_MODE_TOGGLE) } returns false

        val enabled = settingsService.getEnabledToolNames()

        assertEquals(setOf(RestGetTool.NAME, PipelineGetTool.NAME, PipelinePostTool.NAME), enabled)
    }

    @Test
    fun `pipeline post allowlist defaults to all paths enabled`() {
        mockkStatic(TeamCityProperties::class)
        every { TeamCityProperties.getPropertyOrNull(MCP_PIPELINE_POST_ALLOWED_PATHS) } returns null

        assertEquals(null, settingsService.getPipelinePostAllowedPaths())
    }

    @Test
    fun `rest post allowlist returns null when property is absent`() {
        mockkStatic(TeamCityProperties::class)
        every { TeamCityProperties.getPropertyOrNull(MCP_REST_POST_ALLOWED_PATHS) } returns null

        assertNull(settingsService.getRestPostAllowedPaths())
    }

    @Test
    fun `rest post allowlist parses comma-separated paths`() {
        mockkStatic(TeamCityProperties::class)
        every { TeamCityProperties.getPropertyOrNull(MCP_REST_POST_ALLOWED_PATHS) } returns
            "/app/rest/buildQueue,/app/rest/investigations"

        assertEquals(
            setOf("/app/rest/buildQueue", "/app/rest/investigations"),
            settingsService.getRestPostAllowedPaths()
        )
    }

    @Test
    fun `default tools include introduce_yourself only when development mode is on`() {
        mockkStatic(TeamCityProperties::class)
        every { TeamCityProperties.getPropertyOrNull(MCP_TOOLS_ENABLED) } returns null
        every { TeamCityProperties.getBooleanOrTrue(MCP_PIPELINE_TOGGLE) } returns false
        every { TeamCityProperties.getBoolean(MCP_BRAVE_MODE_TOGGLE) } returns false
        every { TeamCityProperties.getBoolean("teamcity.development.mode") } returns false

        val prod = settingsService.getEnabledToolNames()
        assertFalse(prod.contains(IntroduceYourselfTool.NAME))

        every { TeamCityProperties.getBoolean("teamcity.development.mode") } returns true
        val dev = settingsService.getEnabledToolNames()
        assertTrue(dev.contains(IntroduceYourselfTool.NAME))
    }

    @Test
    fun `default resources include introduce_yourself only when development mode is on`() {
        mockkStatic(TeamCityProperties::class)
        every { TeamCityProperties.getPropertyOrNull(MCP_RESOURCES_ENABLED) } returns null
        every { TeamCityProperties.getBooleanOrTrue(MCP_PIPELINE_TOGGLE) } returns false
        every { TeamCityProperties.getBoolean(MCP_BRAVE_MODE_TOGGLE) } returns false
        every { TeamCityProperties.getBoolean("teamcity.development.mode") } returns false

        val prod = settingsService.getEnabledResourceNames()
        assertFalse(prod.contains(IntroduceYourselfResource.SETTINGS_NAME))

        every { TeamCityProperties.getBoolean("teamcity.development.mode") } returns true
        val dev = settingsService.getEnabledResourceNames()
        assertTrue(dev.contains(IntroduceYourselfResource.SETTINGS_NAME))
    }

    @Test
    fun `default tools include rest put and delete only when brave mode is on`() {
        mockkStatic(TeamCityProperties::class)
        every { TeamCityProperties.getPropertyOrNull(MCP_TOOLS_ENABLED) } returns null
        every { TeamCityProperties.getBooleanOrTrue(MCP_PIPELINE_TOGGLE) } returns false
        every { TeamCityProperties.getBoolean(MCP_BRAVE_MODE_TOGGLE) } returns false

        val offEnabled = settingsService.getEnabledToolNames()
        assertFalse(offEnabled.contains(RestPutTool.NAME))
        assertFalse(offEnabled.contains(RestDeleteTool.NAME))

        every { TeamCityProperties.getBoolean(MCP_BRAVE_MODE_TOGGLE) } returns true
        val onEnabled = settingsService.getEnabledToolNames()
        assertTrue(onEnabled.contains(RestPutTool.NAME))
        assertTrue(onEnabled.contains(RestDeleteTool.NAME))
    }

    @Test
    fun `default resources include only read only pipeline guide when brave mode is off`() {
        mockkStatic(TeamCityProperties::class)
        every { TeamCityProperties.getPropertyOrNull(MCP_RESOURCES_ENABLED) } returns null
        every { TeamCityProperties.getBooleanOrTrue(MCP_PIPELINE_TOGGLE) } returns true
        every { TeamCityProperties.getBoolean(MCP_BRAVE_MODE_TOGGLE) } returns false

        val enabled = settingsService.getEnabledResourceNames()

        assertTrue(enabled.contains(PipelineReadOnlyGuideResource.SETTINGS_NAME))
    }

    @Test
    fun `default resources include only brave pipeline guide when brave mode is on`() {
        mockkStatic(TeamCityProperties::class)
        every { TeamCityProperties.getPropertyOrNull(MCP_RESOURCES_ENABLED) } returns null
        every { TeamCityProperties.getBooleanOrTrue(MCP_PIPELINE_TOGGLE) } returns true
        every { TeamCityProperties.getBoolean(MCP_BRAVE_MODE_TOGGLE) } returns true

        val enabled = settingsService.getEnabledResourceNames()

        assertTrue(enabled.contains(PipelineBraveGuideResource.SETTINGS_NAME))
    }
}
