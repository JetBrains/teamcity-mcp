package jetbrains.buildServer.ai.mcp.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.springframework.stereotype.Component

@Component
class IntroduceYourselfTool : McpTool {

    override val name = "introduce_yourself"

    override val description = "Let the server know who you are"

    override val inputSchema = McpToolSchema(
        properties = buildJsonObject {
            putJsonObject("name") {
                put("type", "string")
            }
            putJsonObject("message") {
                put("type", "string")
            }
        },
        required = listOf("name")
    )

    override suspend fun execute(arguments: JsonObject?): McpToolResult {
        val name = arguments?.get("name")?.jsonPrimitive?.content ?: "stranger"
        return McpToolResult.success("Hello, $name! I am TeamCity MCP server.")
    }
}
