package jetbrains.buildServer.ai.mcp.tools.pipeline

import io.mockk.every
import io.mockk.mockk
import jetbrains.buildServer.ai.mcp.SettingsService
import jetbrains.buildServer.ai.mcp.tools.rest.RestApiResponse
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

class PipelinePostToolTest {
    private val json = Json

    private fun settings(allowedPaths: Set<String>? = null): SettingsService {
        val service = mockk<SettingsService>()
        every { service.getPipelinePostAllowedPaths() } returns allowedPaths
        return service
    }

    private fun tool(
        client: PipelineApiClient? = CapturingClient(),
        settingsService: SettingsService = settings()
    ) = PipelinePostTool(client, settingsService)

    private class CapturingClient(
        private val response: RestApiResponse = RestApiResponse("{}")
    ) : PipelineApiClient {
        var capturedPath: String? = null
        var capturedQuery: String? = null
        var capturedBody: String? = null

        override suspend fun get(path: String, query: String): RestApiResponse =
            throw UnsupportedOperationException()

        override suspend fun post(path: String, query: String, body: String): RestApiResponse {
            capturedPath = path
            capturedQuery = query
            capturedBody = body
            return response
        }

        override suspend fun delete(path: String, query: String): RestApiResponse =
            throw UnsupportedOperationException()
    }

    @Test
    fun `name is teamcity_pipeline_post`() {
        assertEquals("teamcity_pipeline_post", tool().name)
    }

    @Test
    fun `description explains brave mode and absent allowlist semantics`() {
        val description = tool().description
        assertTrue(description.contains("Brave mode must be enabled"))
        assertTrue(description.contains("teamcity.ai.mcp.pipeline.post.allowed.paths"))
        assertTrue(description.contains("If that property is absent, all `/app/pipeline...` POST paths are enabled."))
    }

    @Test
    fun `execute rejects non pipeline path`() = runBlocking {
        val result = tool(settingsService = settings(setOf("/app/pipeline/schema/generate"))).execute(buildJsonObject {
            put("path", "/app/rest/pipelines/id:Pipe1/run")
            put("body", "{}")
        })

        assertTrue(result.isError)
        assertTrue(result.text.contains("/app/pipeline"))
    }

    @Test
    fun `execute rejects invalid json body`() = runBlocking {
        val result = tool(settingsService = settings(setOf("/app/pipeline/schema/generate"))).execute(buildJsonObject {
            put("path", "/app/pipeline/schema/generate")
            put("body", "not json")
        })

        assertTrue(result.isError)
        assertTrue(result.text.contains("valid JSON"))
    }

    @Test
    fun `execute rejects non object json body`() = runBlocking {
        val result = tool(settingsService = settings(setOf("/app/pipeline/schema/generate"))).execute(buildJsonObject {
            put("path", "/app/pipeline/schema/generate")
            put("body", "[]")
        })

        assertTrue(result.isError)
        assertTrue(result.text.contains("JSON object"))
    }

    @Test
    fun `absent allowlist enables all pipeline post paths`() = runBlocking {
        val client = CapturingClient()

        val result = tool(client = client, settingsService = settings(null)).execute(buildJsonObject {
            put("path", "/app/pipeline/schema/generate")
            put("body", "{}")
        })

        assertFalse(result.isError)
        assertEquals("/app/pipeline/schema/generate", client.capturedPath)
    }

    @Test
    fun `empty allowlist blocks all pipeline post paths`() = runBlocking {
        val result = tool(settingsService = settings(emptySet())).execute(buildJsonObject {
            put("path", "/app/pipeline/schema/generate")
            put("body", "{}")
        })

        assertTrue(result.isError)
        assertTrue(result.text.contains("not allowed"))
    }

    @Test
    fun `error message shows allowed pipeline post paths`() = runBlocking {
        val settings = mockk<SettingsService>()
        every { settings.getPipelinePostAllowedPaths() } returns setOf("/app/pipeline/schema/generate")

        val result = tool(settingsService = settings).execute(buildJsonObject {
            put("path", "/app/pipeline/repository/branches")
            put("body", "{}")
        })

        assertTrue(result.isError)
        assertTrue(result.text.contains("/app/pipeline/schema/generate"))
    }

    @Test
    fun `exact allowlist entry permits post`() = runBlocking {
        val client = CapturingClient()

        val result = tool(
            client = client,
            settingsService = settings(setOf("/app/pipeline/schema/generate"))
        ).execute(buildJsonObject {
            put("path", "/app/pipeline/schema/generate/")
            put("query", "?pipelineId=Pipe1")
            put("body", """{"yaml":"jobs:{}"}""")
        })

        assertFalse(result.isError)
        assertEquals("/app/pipeline/schema/generate", client.capturedPath)
        assertEquals("pipelineId=Pipe1", client.capturedQuery)
    }

    @Test
    fun `wildcard allowlist entry permits editor helper endpoint`() = runBlocking {
        val client = CapturingClient()

        val result = tool(
            client = client,
            settingsService = settings(setOf("/app/pipeline/*/parameters/resolve"))
        ).execute(buildJsonObject {
            put("path", "/app/pipeline/Pipe1/parameters/resolve")
            put("body", """{"pipeline":{"yaml":"jobs:{}"}}""")
        })

        assertFalse(result.isError)
        assertEquals("/app/pipeline/Pipe1/parameters/resolve", client.capturedPath)
    }

    @Test
    fun `execute returns envelope for successful response`() = runBlocking {
        val client = CapturingClient(RestApiResponse("""{"status":"OK"}"""))

        val result = tool(
            client = client,
            settingsService = settings(setOf("/app/pipeline/repository/branches"))
        ).execute(buildJsonObject {
            put("path", "/app/pipeline/repository/branches")
            put("body", """{"repository":"demo"}""")
        })

        assertFalse(result.isError)
        val payload = json.parseToJsonElement(result.text).jsonObject
        assertEquals(
            "/app/pipeline/repository/branches",
            payload.getValue("meta").jsonObject.getValue("url").jsonPrimitive.content
        )
        assertEquals("OK", payload.getValue("body").jsonObject.getValue("status").jsonPrimitive.content)
    }
}
