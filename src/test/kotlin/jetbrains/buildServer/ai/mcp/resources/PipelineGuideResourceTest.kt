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
    fun `read only guide explains read only mode`() {
        val content = PipelineReadOnlyGuideResource().read()
        assertTrue(content.contains("read-only pipeline support"))
        assertTrue(content.contains("teamcity_pipeline_get"))
        assertTrue(content.contains("/app/pipeline"))
        assertTrue(content.contains("/app/rest/pipelines"))
        assertTrue(content.contains("alternative to classic TeamCity build chains"))
    }

    @Test
    fun `brave guide explains brave mode and allowed post paths`() {
        val content = PipelineBraveGuideResource().read()
        assertTrue(content.contains("Brave Mode"))
        assertTrue(content.contains("teamcity_pipeline_get"))
        assertTrue(content.contains("teamcity_pipeline_post"))
        assertTrue(content.contains("teamcity.ai.mcp.pipeline.enabled"))
        assertTrue(content.contains("teamcity.ai.mcp.braveMode.enabled"))
        assertTrue(content.contains("teamcity.ai.mcp.pipeline.post.allowed.paths"))
        assertTrue(content.contains("/app/pipeline/schema/generate"))
    }
}
