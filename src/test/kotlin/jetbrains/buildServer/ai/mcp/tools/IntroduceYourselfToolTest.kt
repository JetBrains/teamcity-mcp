package jetbrains.buildServer.ai.mcp.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class IntroduceYourselfToolTest {

    private val tool = IntroduceYourselfTool()

    @Test
    fun `name is introduce_yourself`() {
        assertEquals("introduce_yourself", tool.name)
    }

    @Test
    fun `execute with name argument returns greeting`() = runBlocking {
        val args = buildJsonObject { put("name", "Alice") }
        val result = tool.execute(args)
        assertEquals("Hello, Alice! I am TeamCity MCP server.", result.text)
        assertFalse(result.isError)
    }

    @Test
    fun `execute without name returns greeting for stranger`() = runBlocking {
        val result = tool.execute(null)
        assertEquals("Hello, stranger! I am TeamCity MCP server.", result.text)
        assertFalse(result.isError)
    }
}
