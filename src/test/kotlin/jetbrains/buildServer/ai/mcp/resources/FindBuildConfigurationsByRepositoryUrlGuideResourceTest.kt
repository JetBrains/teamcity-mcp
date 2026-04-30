package jetbrains.buildServer.ai.mcp.resources

import jetbrains.buildServer.ai.mcp.resources.rest.FindBuildConfigurationsByRepositoryUrlGuideResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FindBuildConfigurationsByRepositoryUrlGuideResourceTest {

    private val resource = FindBuildConfigurationsByRepositoryUrlGuideResource()

    @Test
    fun `settingsName is find_build_configurations_by_repository_url_guide`() {
        assertEquals("find_build_configurations_by_repository_url_guide", resource.shortName)
    }

    @Test
    fun `uri is teamcity info build_configurations_by_repository_url`() {
        assertEquals("teamcity://guides/build_configurations_by_repository_url", resource.uri)
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
        assertTrue(content.contains("/app/rest/buildTypes"), "Content should include build configurations API")
        assertTrue(content.contains("/app/rest/vcs-roots"), "Content should mention VCS roots API")
    }
}