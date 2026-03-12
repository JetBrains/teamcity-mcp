package jetbrains.buildServer.ai.mcp.tests.smoke

import jetbrains.buildServer.ai.mcp.McpIntegrationTestBase
import jetbrains.buildServer.ai.mcp.framework.TcServerConfig
import jetbrains.buildServer.ai.mcp.framework.TestMcpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissionRestrictedTokenTest : McpIntegrationTestBase() {

    @Test
    fun `teamcity rest get respects permission restricted token`() {
        restrictedClient().withSession {
            val projectsResult = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/projects",
                "query" to "locator=parentProject:(id:_Root),count:100&fields=project(id,name)"
            ))
            assertFalse(projectsResult.isError, "projects query should succeed: ${projectsResult.content}")

            val projectIds = extractBody(projectsResult)["project"]
                ?.jsonArray
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                ?: emptyList()

            assertTrue(VISIBLE_PROJECT_ID in projectIds, "Visible project must be returned, got $projectIds")
            assertFalse(HIDDEN_PROJECT_ID in projectIds, "Hidden project must not be returned, got $projectIds")

            val buildTypesResult = callTool("teamcity_rest_get", mapOf(
                "path" to "/app/rest/buildTypes",
                "query" to "locator=count:100&fields=buildType(id,name,projectId)"
            ))
            assertFalse(buildTypesResult.isError, "buildTypes query should succeed: ${buildTypesResult.content}")

            val buildTypes = extractBody(buildTypesResult)["buildType"]?.jsonArray ?: JsonArray(emptyList())
            val buildTypeIds = buildTypes.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
            val buildTypeProjectIds = buildTypes.mapNotNull { it.jsonObject["projectId"]?.jsonPrimitive?.content }.toSet()

            assertTrue(VISIBLE_BUILD_TYPE_ID in buildTypeIds, "Visible build type must be returned, got $buildTypeIds")
            assertFalse(HIDDEN_BUILD_TYPE_ID in buildTypeIds, "Hidden build type must not be returned, got $buildTypeIds")
            assertEquals(setOf(VISIBLE_PROJECT_ID), buildTypeProjectIds, "Restricted token should only expose build types from the visible project")
        }
    }

    private fun restrictedClient(): TestMcpClient {
        val restrictedToken = prop("TC_SERVER_RESTRICTED_TOKEN")
            ?: error("TC_SERVER_RESTRICTED_TOKEN system property or env var is required")
        return TestMcpClient(TcServerConfig(baseUrl = serverConfig.baseUrl, bearerToken = restrictedToken))
    }

    private fun prop(name: String): String? = System.getProperty(name) ?: System.getenv(name)

    private fun extractBody(result: TestMcpClient.ToolResult): JsonObject {
        val text = result.content.first().text
        val envelope = Json.parseToJsonElement(text).jsonObject
        return envelope["body"]?.jsonObject ?: JsonObject(emptyMap())
    }

    companion object {
        private const val VISIBLE_PROJECT_ID = "McpPermissionVisible"
        private const val HIDDEN_PROJECT_ID = "McpPermissionHidden"
        private const val VISIBLE_BUILD_TYPE_ID = "McpPermissionVisible_Build"
        private const val HIDDEN_BUILD_TYPE_ID = "McpPermissionHidden_Build"
    }
}
