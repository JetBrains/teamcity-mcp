package jetbrains.buildServer.ai.mcp.resources.rest

import jetbrains.buildServer.ai.mcp.resources.BraveModeAwareMcpResource
import org.springframework.stereotype.Component

@Component
class PipelineReadOnlyGuideResource : BraveModeAwareMcpResource {
    companion object {
        const val SETTINGS_NAME = "pipeline_guide"
    }

    override val brave = false

    override val uri = "teamcity://guides/pipelines"
    override val name = "TeamCity Pipelines Guide"
    override val shortName = SETTINGS_NAME
    override val description =
        "Guide for AI agents working with TeamCity Pipelines when only read-only MCP pipeline access is enabled."

    override fun read(): String = PipelineGuideContent.readOnly()
}
