package jetbrains.buildServer.ai.mcp.resources.rest

import jetbrains.buildServer.ai.mcp.resources.McpResource
import org.springframework.stereotype.Component

@Component
class PipelineReadOnlyGuideResource : McpResource {
    companion object {
        const val SETTINGS_NAME = "pipeline_guide"
    }

    override val uri = "teamcity://guides/pipelines"
    override val name = "TeamCity Pipelines Guide: Read-Only Mode"
    override val shortName = SETTINGS_NAME
    override val description =
        "Guide for AI agents working with TeamCity Pipelines when only read-only MCP pipeline access is enabled."

    override fun read(): String = PipelineGuideContent.readOnly()
}
