package jetbrains.buildServer.ai.mcp

import jetbrains.buildServer.ai.mcp.resources.IntroduceYourselfResource
import jetbrains.buildServer.ai.mcp.resources.rest.BuildFailureAnalysisGuideResource
import jetbrains.buildServer.ai.mcp.resources.rest.FindBuildConfigurationsByRepositoryUrlGuideResource
import jetbrains.buildServer.ai.mcp.resources.rest.PipelineBraveGuideResource
import jetbrains.buildServer.ai.mcp.resources.rest.PipelineReadOnlyGuideResource
import jetbrains.buildServer.ai.mcp.resources.rest.RestApiGuideResource
import jetbrains.buildServer.ai.mcp.tools.IntroduceYourselfTool
import jetbrains.buildServer.ai.mcp.tools.pipeline.PipelineGetTool
import jetbrains.buildServer.ai.mcp.tools.pipeline.PipelineDeleteTool
import jetbrains.buildServer.ai.mcp.tools.pipeline.PipelinePostTool
import jetbrains.buildServer.ai.mcp.tools.rest.BuildLogTool
import jetbrains.buildServer.ai.mcp.tools.rest.RestDeleteTool
import jetbrains.buildServer.ai.mcp.tools.rest.RestGetTool
import jetbrains.buildServer.ai.mcp.tools.rest.RestPostTool
import jetbrains.buildServer.ai.mcp.tools.rest.RestPutTool
import jetbrains.buildServer.serverSide.TeamCityProperties
import org.springframework.stereotype.Service

const val MCP_FEATURE_TOGGLE = "teamcity.ai.mcp.enabled"
const val MCP_BRAVE_MODE_TOGGLE = "teamcity.ai.mcp.braveMode.enabled"
const val MCP_PIPELINE_TOGGLE = "teamcity.ai.mcp.pipeline.enabled"
const val MCP_TOOLS_ENABLED = "teamcity.ai.mcp.tools.enabled"
const val MCP_RESOURCES_ENABLED = "teamcity.ai.mcp.resources.enabled"
const val MCP_REST_POST_ALLOWED_PATHS = "teamcity.ai.mcp.rest.post.allowed.paths"
const val MCP_PIPELINE_POST_ALLOWED_PATHS = "teamcity.ai.mcp.pipeline.post.allowed.paths"

const val BUILD_QUEUE_PATH = "/app/rest/buildQueue"

@Service
class SettingsService {

    fun isMcpServerEnabled() = TeamCityProperties.getBooleanOrTrue(MCP_FEATURE_TOGGLE)

    fun isPipelineEnabled() = TeamCityProperties.getBooleanOrTrue(MCP_PIPELINE_TOGGLE)

    fun isBraveModeEnabled() = TeamCityProperties.getBoolean(MCP_BRAVE_MODE_TOGGLE)

    fun isDevelopmentMode() = TeamCityProperties.getBoolean("teamcity.development.mode")

    /**
     * Returns the set of enabled tool names.
     * Empty property: no tools enabled.
     */
    fun getEnabledToolNames(): Set<String> {
        val raw = TeamCityProperties.getPropertyOrNull(MCP_TOOLS_ENABLED) ?: return buildSet {
            add(RestGetTool.NAME)
            add(RestPostTool.NAME)
            add(BuildLogTool.NAME)

            if (isBraveModeEnabled()) {
                add(RestPutTool.NAME)
                add(RestDeleteTool.NAME)
            }

            if (isPipelineEnabled()) {
                add(PipelineGetTool.NAME)
                if (isBraveModeEnabled()) {
                    add(PipelinePostTool.NAME)
                    add(PipelineDeleteTool.NAME)
                }
            }

            if (isDevelopmentMode()) {
                add(IntroduceYourselfTool.NAME)
            }
        }
        if (raw.isBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * Returns the set of enabled resource settings names.
     * Empty property: no resources enabled.
     */
    fun getEnabledResourceNames(): Set<String> {
        val raw = TeamCityProperties.getPropertyOrNull(MCP_RESOURCES_ENABLED)
            ?: return buildSet {
                add(RestApiGuideResource.SETTINGS_NAME)
                add(BuildFailureAnalysisGuideResource.SETTINGS_NAME)
                add(FindBuildConfigurationsByRepositoryUrlGuideResource.SETTINGS_NAME)
                if (isPipelineEnabled()) {
                    if (isBraveModeEnabled()) {
                        add(PipelineBraveGuideResource.SETTINGS_NAME)
                    } else {
                        add(PipelineReadOnlyGuideResource.SETTINGS_NAME)
                    }
                }
                if (isDevelopmentMode()) {
                    add(IntroduceYourselfResource.SETTINGS_NAME)
                }
            }
        if (raw.isBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * Returns the set of REST API paths that the POST tool is allowed to call.
     */
    fun getRestPostAllowedPaths(): Set<String>? = parsePathSet(MCP_REST_POST_ALLOWED_PATHS)

    fun getPipelinePostAllowedPaths(): Set<String>? = parsePathSet(MCP_PIPELINE_POST_ALLOWED_PATHS)

    private fun parsePathSet(propertyName: String): Set<String>? {
        val raw = TeamCityProperties.getPropertyOrNull(propertyName) ?: return null
        if (raw.isBlank()) return emptySet()
        return raw.split(",")
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
