package jetbrains.buildServer.ai.mcp.resources.rest

import jetbrains.buildServer.ai.mcp.resources.McpResource
import org.springframework.stereotype.Component

@Component
class PipelineBraveGuideResource : McpResource {
    companion object {
        const val SETTINGS_NAME = "pipeline_guide"
    }

    override val uri = "teamcity://guides/pipelines"
    override val name = "TeamCity Pipelines Guide: Brave Mode"
    override val shortName = SETTINGS_NAME
    override val description =
        "Guide for AI agents working with TeamCity Pipelines when MCP exposes read and write-style pipeline operations."

    override fun read(): String = PipelineGuideContent.brave()
}
