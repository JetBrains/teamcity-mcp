package jetbrains.buildServer.ai.mcp.resources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RestApiGuideResourceTest {

    private val resource = RestApiGuideResource()

    @Test
    fun `settingsName is rest_api_guide`() {
        assertEquals("rest_api_guide", resource.shortName)
    }

    @Test
    fun `uri is teamcity guides rest-api`() {
        assertEquals("teamcity://guides/rest-api", resource.uri)
    }

    @Test
    fun `mimeType is text markdown`() {
        assertEquals("text/markdown", resource.mimeType)
    }

    @Test
    fun `read returns non-empty markdown content`() {
        val content = resource.read()
        assertTrue(content.isNotBlank(), "Content should not be blank")
        assertTrue(content.contains("# TeamCity REST API Guide"), "Content should contain main heading")
    }

    @Test
    fun `content covers key topics`() {
        val content = resource.read()
        assertTrue(content.contains("teamcity_rest_get"), "Should mention the tool name")
        assertTrue(content.contains("fields"), "Should cover fields parameter")
        assertTrue(content.contains("locator"), "Should cover locators")
        assertTrue(content.contains("/app/rest/"), "Should contain REST API paths")
        assertTrue(content.contains("Pagination"), "Should cover pagination")
    }
}
