package jetbrains.buildServer.ai.mcp.resources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntroduceYourselfResourceTest {

    private val resource = IntroduceYourselfResource()

    @Test
    fun `settingsName is introduce_yourself`() {
        assertEquals("introduce_yourself", resource.shortName)
    }

    @Test
    fun `uri is teamcity info introduce-yourself`() {
        assertEquals("teamcity://info/introduce-yourself", resource.uri)
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
    fun `mimeType is text plain`() {
        assertEquals("text/plain", resource.mimeType)
    }

    @Test
    fun `read returns greeting content`() {
        val content = resource.read()
        assertTrue(content.contains("TeamCity MCP server"), "Content should mention TeamCity MCP server")
        assertFalse(content.isBlank(), "Content should not be blank")
    }
}
