package jetbrains.buildServer.ai.mcp.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeedbackToolTest {

    private val tool = FeedbackTool()

    @Test
    fun `name is feedback`() {
        assertEquals("feedback", tool.name)
    }

    @Test
    fun `execute with category and message returns success`() = runBlocking {
        val args = buildJsonObject {
            put("category", "usability")
            put("message", "Great server!")
        }
        val result = tool.execute(args)
        assertTrue(result.text.contains("Feedback recorded [usability]"))
        assertFalse(result.isError)
    }

    @Test
    fun `execute with tool_name returns success`() = runBlocking {
        val args = buildJsonObject {
            put("category", "tool_quality")
            put("message", "Description is unclear")
            put("tool_name", "introduce_yourself")
        }
        val result = tool.execute(args)
        assertTrue(result.text.contains("Feedback recorded [tool_quality]"))
        assertFalse(result.isError)
    }

    @Test
    fun `execute with invalid category returns error`() = runBlocking {
        val args = buildJsonObject {
            put("category", "invalid_category")
            put("message", "Some feedback")
        }
        val result = tool.execute(args)
        assertTrue(result.isError)
        assertTrue(result.text.contains("Invalid category"))
    }

    @Test
    fun `execute without category returns error`() = runBlocking {
        val args = buildJsonObject {
            put("message", "Some feedback")
        }
        val result = tool.execute(args)
        assertTrue(result.isError)
        assertEquals("'category' parameter is required", result.text)
    }

    @Test
    fun `execute without message returns error`() = runBlocking {
        val args = buildJsonObject {
            put("category", "bug")
        }
        val result = tool.execute(args)
        assertTrue(result.isError)
        assertEquals("'message' parameter is required", result.text)
    }

    @Test
    fun `execute with blank message returns error`() = runBlocking {
        val args = buildJsonObject {
            put("category", "bug")
            put("message", "  ")
        }
        val result = tool.execute(args)
        assertTrue(result.isError)
    }

    @Test
    fun `execute with null args returns error`() = runBlocking {
        val result = tool.execute(null)
        assertTrue(result.isError)
    }

    @Test
    fun `execute truncates long message in response`() = runBlocking {
        val longMessage = "A".repeat(200)
        val args = buildJsonObject {
            put("category", "bug")
            put("message", longMessage)
        }
        val result = tool.execute(args)
        assertFalse(result.isError)
        assertTrue(result.text.contains("..."))
        assertTrue(result.text.length < 200)
    }
}
