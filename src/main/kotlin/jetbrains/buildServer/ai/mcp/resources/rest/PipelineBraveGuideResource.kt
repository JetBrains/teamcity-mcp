package jetbrains.buildServer.ai.mcp.resources.rest

import jetbrains.buildServer.ai.mcp.resources.BraveModeAwareMcpResource
import org.springframework.stereotype.Component

@Component
class PipelineBraveGuideResource : BraveModeAwareMcpResource {
    companion object {
        const val SETTINGS_NAME = "pipeline_guide"
    }

    override val brave = true

    override val uri = "teamcity://guides/pipelines"
    override val name = "TeamCity Pipelines Guide"
    override val shortName = SETTINGS_NAME
    override val description =
        "Guide for AI agents working with TeamCity Pipelines when MCP exposes read and write-style pipeline operations."

    override fun read(): String = PipelineGuideContent.brave()
}
