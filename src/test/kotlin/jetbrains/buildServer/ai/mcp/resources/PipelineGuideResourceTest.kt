package jetbrains.buildServer.ai.mcp.resources

import jetbrains.buildServer.ai.mcp.resources.rest.PipelineBraveGuideResource
import jetbrains.buildServer.ai.mcp.resources.rest.PipelineReadOnlyGuideResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PipelineGuideResourceTest {

    @Test
    fun `read only guide metadata is correct`() {
        val resource = PipelineReadOnlyGuideResource()
        assertEquals("pipeline_guide", resource.shortName)
        assertEquals("teamcity://guides/pipelines", resource.uri)
        assertEquals("text/markdown", resource.mimeType)
    }

    @Test
    fun `brave guide metadata is correct`() {
        val resource = PipelineBraveGuideResource()
        assertEquals("pipeline_guide", resource.shortName)
        assertEquals("teamcity://guides/pipelines", resource.uri)
        assertEquals("text/markdown", resource.mimeType)
    }

    @Test
    fun `read only guide covers read operations`() {
        val content = PipelineReadOnlyGuideResource().read()
        assertTrue(content.contains("teamcity_pipeline_get"))
        assertTrue(content.contains("/app/pipeline"))
        assertTrue(content.contains("/app/rest/pipelines"))
        assertTrue(content.contains("alternative to classic TeamCity build chains"))
        assertTrue(content.contains("locator=buildType:(id:"))
        assertTrue(content.contains("fields=headBuildType(id)"))
    }

    @Test
    fun `brave guide covers write operations and allowed post paths`() {
        val content = PipelineBraveGuideResource().read()
        assertTrue(content.contains("teamcity_pipeline_get"))
        assertTrue(content.contains("teamcity_pipeline_post"))
        assertTrue(content.contains("teamcity_pipeline_delete"))
        assertTrue(content.contains("teamcity.ai.mcp.pipeline.post.allowed.paths"))
        assertTrue(content.contains("/app/pipeline/schema/generate"))
    }
}
