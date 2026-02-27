package jetbrains.buildServer.ai.mcp

import jetbrains.buildServer.ai.mcp.resources.rest.RestApiGuideResource

import jetbrains.buildServer.ai.mcp.tools.FeedbackTool
import jetbrains.buildServer.ai.mcp.tools.rest.RestGetTool
import jetbrains.buildServer.ai.mcp.tools.rest.RestPostTool
import jetbrains.buildServer.serverSide.TeamCityProperties
import org.springframework.stereotype.Service

const val MCP_FEATURE_TOGGLE = "teamcity.ai.mcp.enabled"
const val MCP_TOOLS_ENABLED = "teamcity.ai.mcp.tools.enabled"
const val MCP_RESOURCES_ENABLED = "teamcity.ai.mcp.resources.enabled"
const val MCP_REST_POST_ALLOWED_PATHS = "teamcity.ai.mcp.rest.post.allowed.paths"

const val BUILD_QUEUE_PATH = "/app/rest/buildQueue"

@Service
class SettingsService {

    // TODO: disable by default
    fun isMcpServerEnabled() = TeamCityProperties.getBooleanOrTrue(MCP_FEATURE_TOGGLE)

    /**
     * Returns the set of enabled tool names, or `null` if all tools are enabled,
     * empty string - no tools enabled
     */
    fun getEnabledToolNames(): Set<String> {
        val raw = TeamCityProperties.getPropertyOrNull(MCP_TOOLS_ENABLED)
            ?: return setOf(FeedbackTool.NAME, RestGetTool.NAME, RestPostTool.NAME)
        if (raw.isBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * Returns the set of enabled resource settings names.
     * Empty string: no resources enabled.
     */
    fun getEnabledResourceNames(): Set<String> {
        val raw = TeamCityProperties.getPropertyOrNull(MCP_RESOURCES_ENABLED)
            ?: return setOf(RestApiGuideResource.SETTINGS_NAME)
        if (raw.isBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * Returns the set of REST API paths that the POST tool is allowed to call.
     */
    fun getRestPostAllowedPaths(): Set<String> {
        val raw = TeamCityProperties.getPropertyOrNull(MCP_REST_POST_ALLOWED_PATHS)
            ?: return setOf(BUILD_QUEUE_PATH)
        if (raw.isBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

}
