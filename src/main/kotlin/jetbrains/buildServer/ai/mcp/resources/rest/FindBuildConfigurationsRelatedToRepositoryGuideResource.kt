package jetbrains.buildServer.ai.mcp.resources.rest

import jetbrains.buildServer.ai.mcp.resources.McpResource

class FindBuildConfigurationsRelatedToRepositoryGuideResource : McpResource {
    companion object {
        const val SETTINGS_NAME = "find_build_configurations_related_to_repository_guide"
        private val CONTENT = """
Find build configurations related to a repository guide
            
---


        """.trimIndent()
    }

    override val uri = "teamcity://guides/related-to-repository"

    override val name = "Find Build Configurations Related to Repository Guide"

    override val shortName = SETTINGS_NAME

    override val description =
        "TODO:"

    override val mimeType = "text/markdown"

    override fun read(): String = CONTENT
}