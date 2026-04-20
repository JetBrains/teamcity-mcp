package jetbrains.buildServer.ai.mcp.resources

import jetbrains.buildServer.ai.mcp.resources.rest.RestApiBraveGuideResource
import jetbrains.buildServer.ai.mcp.resources.rest.RestApiGuideResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RestApiBraveGuideResourceTest {

    private val resource = RestApiBraveGuideResource()

    @Test
    fun `shares shortName with the safe rest api guide`() {
        assertEquals(RestApiGuideResource.SETTINGS_NAME, resource.shortName)
    }

    @Test
    fun `shares uri with the safe rest api guide for configurator selection`() {
        assertEquals("teamcity://guides/rest-api", resource.uri)
    }

    @Test
    fun `mimeType is text markdown`() {
        assertEquals("text/markdown", resource.mimeType)
    }

    @Test
    fun `read returns non-empty markdown content`() {
        val content = resource.read()
        assertTrue(content.isNotBlank())
        assertTrue(content.contains("TeamCity REST API Guide"))
    }

    @Test
    fun `content covers safety preamble`() {
        val content = resource.read()
        assertTrue(content.contains("Safety"), "should include a safety section")
        assertTrue(content.contains("Confirm") || content.contains("confirm"),
            "should require user confirmation for destructive ops")
    }

    @Test
    fun `content covers PUT and DELETE tools`() {
        val content = resource.read()
        assertTrue(content.contains("teamcity_rest_put"), "should describe the PUT tool")
        assertTrue(content.contains("teamcity_rest_delete"), "should describe the DELETE tool")
    }

    @Test
    fun `content references the POST allowlist property`() {
        val content = resource.read()
        assertTrue(content.contains("teamcity.ai.mcp.rest.post.allowed.paths"))
    }

    @Test
    fun `content does not claim unsupported put delete allowlists`() {
        val content = resource.read()
        assertTrue(!content.contains("teamcity.ai.mcp.rest.put.allowed.paths"))
        assertTrue(!content.contains("teamcity.ai.mcp.rest.delete.allowed.paths"))
    }

    @Test
    fun `content does not mention brave mode and warns about permissions`() {
        val content = resource.read()
        assertTrue(!content.contains("brave mode", ignoreCase = true))
        assertTrue(content.contains("permissions"))
        assertTrue(content.contains("still fail"))
    }

    @Test
    fun `content documents agent enablement using status field`() {
        val content = resource.read()
        assertTrue(content.contains(""""status":false"""))
        assertTrue(!content.contains("enabled:false"))
    }

    @Test
    fun `content does not claim unsupported generic field updates`() {
        val content = resource.read()
        assertTrue(!content.contains("parentProjectId` (moves the project)"))
        assertTrue(!content.contains("`projectId` (moves the config)"))
        assertTrue(!content.contains("`templateFlag`."))
    }

    @Test
    fun `content links to the versioned REST API documentation`() {
        val content = resource.read()
        assertTrue(content.contains("jetbrains.com/help/teamcity/rest"),
            "should include the versioned docs URL")
        assertTrue(content.contains("/app/rest/swagger.json"),
            "should point at the local swagger spec for exhaustive reference")
    }
}
