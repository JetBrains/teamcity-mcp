package jetbrains.buildServer.ai.mcp.tests.smoke

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PipelineToolSmokeTest : McpIntegrationTestBase() {

    private val json = Json

    @Test
    fun `pipeline get tool is callable end to end when enabled`() {
        mcpClient().use { client ->
            client.withSession {
                val toolNames = listTools().map { it.name }.toSet()
                assumeTrue(
                    "teamcity_pipeline_get" in toolNames,
                    "Pipeline tool support is not enabled on this server (tools: $toolNames) — skipping"
                )
                ensureSeededPipeline()

                val result = callTool("teamcity_pipeline_get", mapOf("path" to "/app/pipeline"))
                assertFalse(result.isError, "teamcity_pipeline_get should succeed, got: ${result.content}")

                val payload = json.parseToJsonElement(result.content.first().text).jsonObject
                val meta = payload.getValue("meta").jsonObject
                assertEquals("/app/pipeline", meta.getValue("url").jsonPrimitive.content)
                assertEquals(200, meta.getValue("statusCode").jsonPrimitive.content.toInt())

                val body = payload["body"]
                val bodyText = payload["bodyText"]?.jsonPrimitive?.content
                assertTrue(
                    body == null || body is JsonArray || body is JsonObject || bodyText != null,
                    "Pipeline tool should return parsed JSON body or bodyText, got: $payload"
                )
                val pipelineNames = extractPipelineNames(payload)
                assertTrue(
                    pipelineNames.contains(seededPipelineName),
                    "Seeded pipeline '$seededPipelineName' must be present, got: $pipelineNames; payload=$payload"
                )

                val sizeHint = if (body is JsonArray) body.jsonArray.size else pipelineNames.size
                println("  ✓ teamcity_pipeline_get returned status 200, pipelineCountHint=$sizeHint, found=$seededPipelineName")
            }
        }
    }

    private fun extractPipelineNames(payload: JsonObject): List<String> {
        val parsedBody = payload["body"]
        if (parsedBody != null) {
            return extractPipelineNames(parsedBody)
        }

        val bodyText = payload["bodyText"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return emptyList()
        val parsedText = runCatching { json.parseToJsonElement(bodyText) }.getOrNull() ?: return emptyList()
        return extractPipelineNames(parsedText)
    }

    private fun extractPipelineNames(element: JsonElement): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { item ->
            item.jsonObject["name"]?.jsonPrimitive?.content
        }
        is JsonObject -> {
            val directName = element["name"]?.jsonPrimitive?.content
            if (directName != null) {
                listOf(directName)
            } else {
                element.values.flatMap { extractPipelineNames(it) }
            }
        }
        else -> emptyList()
    }
}
