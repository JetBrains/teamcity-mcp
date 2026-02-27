package jetbrains.buildServer.ai.mcp.resources

import jetbrains.buildServer.ai.mcp.resources.rest.RestPostGuideResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RestPostGuideResourceTest {

    private val resource = RestPostGuideResource()

    @Test
    fun `settingsName is rest_post_guide`() {
        assertEquals("rest_post_guide", resource.shortName)
    }

    @Test
    fun `uri is teamcity guides rest-post`() {
        assertEquals("teamcity://guides/rest-post", resource.uri)
    }

    @Test
    fun `mimeType is text markdown`() {
        assertEquals("text/markdown", resource.mimeType)
    }

    @Test
    fun `read returns non-empty markdown content`() {
        val content = resource.read()
        assertTrue(content.isNotBlank(), "Content should not be blank")
        assertTrue(content.contains("# TeamCity REST POST Guide"), "Content should contain main heading")
    }

    @Test
    fun `content covers key topics`() {
        val content = resource.read()
        assertTrue(content.contains("teamcity_rest_post"), "Should mention the tool name")
        assertTrue(content.contains("personal"), "Should cover personal builds constraint")
        assertTrue(content.contains("buildQueue"), "Should mention buildQueue endpoint")
        assertTrue(content.contains("buildType"), "Should cover buildType in body")
        assertTrue(content.contains("Error Recovery"), "Should cover error recovery")
    }
}
