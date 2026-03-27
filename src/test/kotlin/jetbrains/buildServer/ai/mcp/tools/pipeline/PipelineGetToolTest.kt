package jetbrains.buildServer.ai.mcp.tools.pipeline

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PipelineGetToolTest {
    private val json = Json

    private fun tool(client: PipelineApiClient? = null) = PipelineGetTool(client)

    private class CapturingClient(
        private val response: jetbrains.buildServer.ai.mcp.tools.rest.RestApiResponse =
            jetbrains.buildServer.ai.mcp.tools.rest.RestApiResponse("{}")
    ) : PipelineApiClient {
        var capturedPath: String? = null
        var capturedQuery: String? = null

        override suspend fun get(path: String, query: String): jetbrains.buildServer.ai.mcp.tools.rest.RestApiResponse {
            capturedPath = path
            capturedQuery = query
            return response
        }

        override suspend fun post(
            path: String,
            query: String,
            body: String
        ): jetbrains.buildServer.ai.mcp.tools.rest.RestApiResponse = throw UnsupportedOperationException()
    }

    @Test
    fun `name is teamcity_pipeline_get`() {
        assertEquals("teamcity_pipeline_get", tool().name)
    }

    @Test
    fun `description explains app pipeline split`() {
        val description = tool().description
        assertTrue(description.contains("/app/pipeline"))
        assertTrue(description.contains("independent from teamcity_rest_get"))
    }

    @Test
    fun `execute rejects non pipeline path`() = runBlocking {
        val result = tool(CapturingClient()).execute(buildJsonObject {
            put("path", "/app/rest/pipelines")
        })

        assertTrue(result.isError)
        assertTrue(result.text.contains("/app/pipeline"))
    }

    @Test
    fun `execute rejects query string inside path`() = runBlocking {
        val result = tool(CapturingClient()).execute(buildJsonObject {
            put("path", "/app/pipeline/Test?parentProjectExtId=Root")
        })

        assertTrue(result.isError)
        assertTrue(result.text.contains("query"))
    }

    @Test
    fun `execute strips leading question mark from query and normalizes trailing slash`() = runBlocking {
        val client = CapturingClient()

        val result = tool(client).execute(buildJsonObject {
            put("path", "/app/pipeline/Test/")
            put("query", "?parentProjectExtId=Root")
        })

        assertFalse(result.isError)
        assertEquals("/app/pipeline/Test", client.capturedPath)
        assertEquals("parentProjectExtId=Root", client.capturedQuery)
    }

    @Test
    fun `execute returns envelope for successful response`() = runBlocking {
        val client = CapturingClient(
            jetbrains.buildServer.ai.mcp.tools.rest.RestApiResponse("""{"id":"Pipe1"}""", 200)
        )

        val result = tool(client).execute(buildJsonObject {
            put("path", "/app/pipeline/Pipe1")
        })

        assertFalse(result.isError)
        val payload = json.parseToJsonElement(result.text).jsonObject
        assertEquals("/app/pipeline/Pipe1", payload.getValue("meta").jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals("Pipe1", payload.getValue("body").jsonObject.getValue("id").jsonPrimitive.content)
    }
}
