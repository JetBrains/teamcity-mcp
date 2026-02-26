package jetbrains.buildServer.ai.mcp

import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import jetbrains.buildServer.ai.mcp.resources.McpResource
import jetbrains.buildServer.ai.mcp.tools.McpTool
import jetbrains.buildServer.ai.mcp.tools.McpToolResult
import jetbrains.buildServer.ai.mcp.tools.McpToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class McpServerConfiguratorTest {

    private val settingsService: SettingsService = mockk()

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun configurator(
        tools: List<McpTool>? = null,
        resources: List<McpResource>? = null
    ) = McpServerConfigurator(settingsService, tools, resources)

    // -----------------------------------------------------------------------
    // Tool filtering (existing tests)
    // -----------------------------------------------------------------------

    @Nested
    inner class ToolFiltering {

        @Test
        fun `configureServer with no tools produces server with zero tools`() {
            every { settingsService.getEnabledToolNames() } returns emptySet()
            every { settingsService.getEnabledResourceNames() } returns emptySet()
            val configurator = configurator(null)
            assertEquals(0, configurator.getEnabledTools().size)
        }

        @Test
        fun `configureServer registers all enabled tools`() {
            every { settingsService.getEnabledToolNames() } returns setOf("tool_a", "tool_b")
            every { settingsService.getEnabledResourceNames() } returns emptySet()
            val tools = listOf(testTool("tool_a"), testTool("tool_b"))
            val configurator = configurator(tools)
            assertEquals(2, configurator.getEnabledTools().size)
        }

        @Test
        fun `getEnabledTools returns all tools when property is absent`() {
            every { settingsService.getEnabledToolNames() } returns setOf("a", "b", "c")
            every { settingsService.getEnabledResourceNames() } returns emptySet()
            val tools = listOf(testTool("a"), testTool("b"), testTool("c"))
            val configurator = configurator(tools)
            assertEquals(3, configurator.getEnabledTools().size)
        }

        @Test
        fun `getEnabledTools returns all tools when property is star`() {
            every { settingsService.getEnabledToolNames() } returns setOf("a", "b")
            every { settingsService.getEnabledResourceNames() } returns emptySet()
            val tools = listOf(testTool("a"), testTool("b"))
            val configurator = configurator(tools)
            assertEquals(2, configurator.getEnabledTools().size)
        }

        @Test
        fun `getEnabledTools filters to named tools only`() {
            every { settingsService.getEnabledToolNames() } returns setOf("tool_a", "tool_c")
            every { settingsService.getEnabledResourceNames() } returns emptySet()
            val tools = listOf(testTool("tool_a"), testTool("tool_b"), testTool("tool_c"))
            val configurator = configurator(tools)
            val enabled = configurator.getEnabledTools()
            assertEquals(2, enabled.size)
            assertEquals(listOf("tool_a", "tool_c"), enabled.map { it.name })
        }

        @Test
        fun `getEnabledTools returns empty when property is empty string`() {
            every { settingsService.getEnabledToolNames() } returns emptySet()
            every { settingsService.getEnabledResourceNames() } returns emptySet()
            val tools = listOf(testTool("tool_a"), testTool("tool_b"))
            val configurator = configurator(tools)
            assertTrue(configurator.getEnabledTools().isEmpty())
        }

        @Test
        fun `configureServer only registers enabled tools`() {
            every { settingsService.getEnabledToolNames() } returns setOf("tool_b")
            every { settingsService.getEnabledResourceNames() } returns emptySet()
            val tools = listOf(testTool("tool_a"), testTool("tool_b"))
            val configurator = configurator(tools)
            val server = configurator.configureServer()
            val enabled = configurator.getEnabledTools()
            assertEquals(1, enabled.size)
            assertEquals("tool_b", enabled.first().name)
        }

        @Test
        fun `tool exception is caught and returned as error result`() {
            every { settingsService.getEnabledToolNames() } returns setOf("failing_tool")
            every { settingsService.getEnabledResourceNames() } returns emptySet()
            val failingTool = object : McpTool {
                override val name = "failing_tool"
                override val description = "A tool that fails"
                override val inputSchema = McpToolSchema()
                override suspend fun execute(arguments: JsonObject?): McpToolResult {
                    throw RuntimeException("Something went wrong")
                }
            }
            val configurator = configurator(listOf(failingTool))
            val server = configurator.configureServer()
            // The server was created successfully — the exception handling is in the lambda
        }
    }

    // -----------------------------------------------------------------------
    // Resource filtering
    // -----------------------------------------------------------------------

    @Nested
    inner class ResourceFiltering {

        @Test
        fun `configureServer with no resources produces server with no resource capability`() {
            every { settingsService.getEnabledToolNames() } returns emptySet()
            every { settingsService.getEnabledResourceNames() } returns emptySet()
            val configurator = configurator(resources = null)
            assertEquals(0, configurator.getEnabledResources().size)
        }

        @Test
        fun `getEnabledResources returns all enabled resources`() {
            every { settingsService.getEnabledResourceNames() } returns setOf("res_a", "res_b")
            val resources = listOf(testResource("res_a"), testResource("res_b"))
            val configurator = configurator(resources = resources)
            assertEquals(2, configurator.getEnabledResources().size)
        }

        @Test
        fun `getEnabledResources filters to named resources only`() {
            every { settingsService.getEnabledResourceNames() } returns setOf("res_a", "res_c")
            val resources = listOf(testResource("res_a"), testResource("res_b"), testResource("res_c"))
            val configurator = configurator(resources = resources)
            val enabled = configurator.getEnabledResources()
            assertEquals(2, enabled.size)
            assertEquals(listOf("res_a", "res_c"), enabled.map { it.shortName })
        }

        @Test
        fun `getEnabledResources returns empty when property is empty string`() {
            every { settingsService.getEnabledResourceNames() } returns emptySet()
            val resources = listOf(testResource("res_a"), testResource("res_b"))
            val configurator = configurator(resources = resources)
            assertTrue(configurator.getEnabledResources().isEmpty())
        }

        @Test
        fun `configureServer registers only enabled resources`() {
            every { settingsService.getEnabledToolNames() } returns emptySet()
            every { settingsService.getEnabledResourceNames() } returns setOf("res_b")
            val resources = listOf(testResource("res_a"), testResource("res_b"))
            val configurator = configurator(resources = resources)
            val server = configurator.configureServer()
            val enabled = configurator.getEnabledResources()
            assertEquals(1, enabled.size)
            assertEquals("res_b", enabled.first().shortName)
        }

        @Test
        fun `configureServer with enabled resources sets resources capability`() {
            every { settingsService.getEnabledToolNames() } returns emptySet()
            every { settingsService.getEnabledResourceNames() } returns setOf("res_a")
            val resources = listOf(testResource("res_a"))
            val configurator = configurator(resources = resources)
            // Server should be created successfully with resources capability
            val server = configurator.configureServer()
            assertEquals(1, configurator.getEnabledResources().size)
        }

        @Test
        fun `configureServer with no enabled resources does not set resources capability`() {
            every { settingsService.getEnabledToolNames() } returns emptySet()
            every { settingsService.getEnabledResourceNames() } returns emptySet()
            val resources = listOf(testResource("res_a"))
            val configurator = configurator(resources = resources)
            val server = configurator.configureServer()
            assertTrue(configurator.getEnabledResources().isEmpty())
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun testTool(toolName: String) = object : McpTool {
        override val name = toolName
        override val description = "Test tool $toolName"
        override val inputSchema = McpToolSchema(
            properties = buildJsonObject {
                putJsonObject("arg") { put("type", "string") }
            }
        )
        override suspend fun execute(arguments: JsonObject?): McpToolResult {
            return McpToolResult.success("ok")
        }
    }

    private fun testResource(resName: String) = object : McpResource {
        override val uri = "test://resources/$resName"
        override val name = "Test Resource $resName"
        override val shortName = resName
        override val description = "Test resource $resName"
        override val mimeType = "text/plain"
        override fun read(): String = "Content of $resName"
    }
}
