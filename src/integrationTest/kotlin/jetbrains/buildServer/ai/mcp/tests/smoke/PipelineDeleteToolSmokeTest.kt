package jetbrains.buildServer.ai.mcp.tests.smoke

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PipelineDeleteToolSmokeTest : McpIntegrationTestBase() {

    private val json = Json

    @Test
    fun `pipeline delete tool removes pipeline and its project`() {
        mcpClient().use { client ->
            client.withSession {
                val toolNames = listTools().map { it.name }.toSet()
                assumeTrue(
                    "teamcity_pipeline_delete" in toolNames,
                    "Pipeline delete tool is not enabled on this server (tools: $toolNames) — skipping"
                )

                // Create a disposable pipeline
                val pipelineId = createPipeline("Delete Test Pipeline ${System.currentTimeMillis()}")
                assertTrue(pipelineExists(pipelineId), "Pipeline '$pipelineId' should exist after creation")

                // Delete it via the MCP tool
                val result = callTool("teamcity_pipeline_delete", mapOf("path" to "/app/pipeline/$pipelineId"))
                assertFalse(result.isError, "teamcity_pipeline_delete should succeed, got: ${result.content}")

                val payload = json.parseToJsonElement(result.content.first().text).jsonObject
                val meta = payload.getValue("meta").jsonObject
                assertEquals(200, meta.getValue("statusCode").jsonPrimitive.content.toInt(),
                    "Delete should return 200, got: $payload")

                // Verify the pipeline no longer exists
                assertFalse(pipelineExists(pipelineId), "Pipeline '$pipelineId' should not exist after deletion")

                println("  ✓ teamcity_pipeline_delete removed pipeline '$pipelineId' successfully")
            }
        }
    }

    @Test
    fun `pipeline delete tool returns error for nonexistent pipeline`() {
        mcpClient().use { client ->
            client.withSession {
                val toolNames = listTools().map { it.name }.toSet()
                assumeTrue(
                    "teamcity_pipeline_delete" in toolNames,
                    "Pipeline delete tool is not enabled on this server (tools: $toolNames) — skipping"
                )

                val result = callTool("teamcity_pipeline_delete", mapOf("path" to "/app/pipeline/NonExistentPipeline_12345"))
                val payload = json.parseToJsonElement(result.content.first().text).jsonObject
                val meta = payload.getValue("meta").jsonObject
                val statusCode = meta.getValue("statusCode").jsonPrimitive.content.toInt()

                assertTrue(statusCode in 400..499, "Delete of nonexistent pipeline should return 4xx, got: $statusCode")
                println("  ✓ teamcity_pipeline_delete correctly returned $statusCode for nonexistent pipeline")
            }
        }
    }

    @Test
    fun `pipeline delete tool rejects bare path without pipeline id`() {
        mcpClient().use { client ->
            client.withSession {
                val toolNames = listTools().map { it.name }.toSet()
                assumeTrue(
                    "teamcity_pipeline_delete" in toolNames,
                    "Pipeline delete tool is not enabled on this server (tools: $toolNames) — skipping"
                )

                val result = callTool("teamcity_pipeline_delete", mapOf("path" to "/app/pipeline"))
                assertTrue(result.isError, "Delete without pipeline id should return an error")
                println("  ✓ teamcity_pipeline_delete correctly rejected bare /app/pipeline path")
            }
        }
    }

    @Test
    fun `pipeline delete tool rejects sub-resource paths`() {
        mcpClient().use { client ->
            client.withSession {
                val toolNames = listTools().map { it.name }.toSet()
                assumeTrue(
                    "teamcity_pipeline_delete" in toolNames,
                    "Pipeline delete tool is not enabled on this server (tools: $toolNames) — skipping"
                )

                val result = callTool("teamcity_pipeline_delete", mapOf("path" to "/app/pipeline/SomeId/notifications"))
                assertTrue(result.isError, "Delete on sub-resource path should return an error")
                println("  ✓ teamcity_pipeline_delete correctly rejected sub-resource path")
            }
        }
    }
}
