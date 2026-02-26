package jetbrains.buildServer.ai.mcp.resources

import org.springframework.stereotype.Component

@Component
class IntroduceYourselfResource : McpResource {

    companion object {
        const val SETTINGS_NAME = "introduce_yourself"
    }

    override val uri = "teamcity://info/introduce-yourself"

    override val name = "TeamCity MCP Server Introduction"

    override val shortName = SETTINGS_NAME

    override val description = "Basic information about the TeamCity MCP server and its capabilities"

    override val mimeType = "text/plain"

    override fun read(): String =
        "Hello! I am TeamCity MCP server. I provide tools and resources to help AI agents interact with TeamCity."
}
