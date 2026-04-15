package jetbrains.buildServer.ai.mcp.resources

import jetbrains.buildServer.ai.mcp.resources.rest.FindProjectsRelatedToRepositoryGuideResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FindProjectsRelatedToRepositoryGuideResourceTest {

    private val resource = FindProjectsRelatedToRepositoryGuideResource()

    @Test
    fun `settingsName is find_projects_related_to_repository_guide`() {
        assertEquals("find_projects_related_to_repository_guide", resource.shortName)
    }

    @Test
    fun `uri is teamcity info projects-related-to-repository`() {
        assertEquals("teamcity://guides/projects-related-to-repository", resource.uri)
    }

    @Test
    fun `name is non-blank`() {
        assertTrue(resource.name.isNotBlank())
    }

    @Test
    fun `description is non-blank`() {
        assertTrue(resource.description.isNotBlank())
    }

    @Test
    fun `mimeType is text markdown`() {
        assertEquals("text/markdown", resource.mimeType)
    }

    @Test
    fun `read returns get find projects related to repository content`() {
        val content = resource.read()
        assertFalse(content.isBlank(), "Content should not be blank")
        assertTrue(content.contains("/app/rest/projects"))
    }
}