package jetbrains.buildServer.ai.mcp.resources

import jetbrains.buildServer.ai.mcp.resources.rest.RestApiGuideResource
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
    fun `content covers GET topics`() {
        val content = resource.read()
        assertTrue(content.contains("teamcity_rest_get"), "Should mention the GET tool name")
        assertTrue(content.contains("fields"), "Should cover fields parameter")
        assertTrue(content.contains("locator"), "Should cover locators")
        assertTrue(content.contains("/app/rest/"), "Should contain REST API paths")
        assertTrue(content.contains("Pagination"), "Should cover pagination")
    }

    @Test
    fun `content links to REST API documentation`() {
        val content = resource.read()
        assertTrue(content.contains("/app/rest/swagger.json"), "Should link to Swagger API spec")
        assertTrue(content.contains("jetbrains.com/help/teamcity/rest"),
            "Should link to official JetBrains REST API documentation")
    }

    @Test
    fun `content covers POST topics`() {
        val content = resource.read()
        assertTrue(content.contains("teamcity_rest_post"), "Should mention the POST tool name")
        assertTrue(content.contains("personal"), "Should cover personal builds constraint")
        assertTrue(content.contains("buildQueue"), "Should mention buildQueue endpoint")
        assertTrue(content.contains("buildType"), "Should cover buildType in body")
        assertTrue(content.contains("moveToTop=true"), "Should document query-based POST flags")
    }

    @Test
    fun `content warns that permissions still apply`() {
        val content = resource.read()
        assertTrue(content.contains("permissions"), "Should mention permissions")
        assertTrue(content.contains("still fail"), "Should warn that documented operations may still fail")
    }
}
