package jetbrains.buildServer.ai.mcp.tools.rest

import io.mockk.every
import io.mockk.mockk
import jetbrains.buildServer.ai.mcp.SettingsService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RestDeleteToolTest {
    private val json = Json

    private fun settings(braveModeEnabled: Boolean = true): SettingsService {
        val s = mockk<SettingsService>()
        every { s.isBraveModeEnabled() } returns braveModeEnabled
        return s
    }

    private fun tool(
        client: RestApiClient? = CapturingClient(),
        settingsService: SettingsService = settings()
    ) = RestDeleteTool(client, settingsService)

    private fun parseResultJson(text: String) = json.parseToJsonElement(text).jsonObject

    private class CapturingClient(
        private val response: RestApiResponse = RestApiResponse("")
    ) : RestApiClient {
        var capturedPath: String? = null
        var capturedQuery: String? = null

        override suspend fun get(path: String, query: String): RestApiResponse =
            throw UnsupportedOperationException()

        override suspend fun post(path: String, query: String, body: String): RestApiResponse =
            throw UnsupportedOperationException()

        override suspend fun put(path: String, query: String, body: String): RestApiResponse =
            throw UnsupportedOperationException()

        override suspend fun delete(path: String, query: String): RestApiResponse {
            capturedPath = path
            capturedQuery = query
            return response
        }
    }

    private fun throwingClient(e: Exception) = object : RestApiClient {
        override suspend fun get(path: String, query: String): RestApiResponse = throw e
        override suspend fun post(path: String, query: String, body: String): RestApiResponse = throw e
        override suspend fun put(path: String, query: String, body: String): RestApiResponse = throw e
        override suspend fun delete(path: String, query: String): RestApiResponse = throw e
    }

    @Nested
    inner class Metadata {
        @Test
        fun `name is teamcity_rest_delete`() {
            assertEquals("teamcity_rest_delete", tool().name)
        }

        @Test
        fun `schema declares path and query properties and no body`() {
            val props = tool().inputSchema.properties!!
            assertTrue(props.containsKey("path"))
            assertTrue(props.containsKey("query"))
            assertFalse(props.containsKey("body"))
        }

        @Test
        fun `only path is required`() {
            val required = tool().inputSchema.required!!
            assertEquals(listOf("path"), required)
        }

        @Test
        fun `description warns about irreversible operations`() {
            val description = tool().description
            assertTrue(description.contains("irreversible") || description.contains("destructive"))
            assertTrue(description.contains("DELETE"))
        }
    }

    @Nested
    inner class InputValidation {
        @Test
        fun `brave mode must be enabled`() = runBlocking {
            val result = tool(settingsService = settings(braveModeEnabled = false)).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue/id:1")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("Brave mode"))
        }

        @Test
        fun `execute without arguments returns error mentioning path`() = runBlocking {
            val result = tool().execute(null)
            assertTrue(result.isError)
            assertTrue(result.text.contains("path"))
        }

        @Test
        fun `execute with blank path returns error`() = runBlocking {
            val result = tool().execute(buildJsonObject { put("path", " ") })
            assertTrue(result.isError)
        }

        @Test
        fun `execute with path not starting with app rest returns error`() = runBlocking {
            val result = tool().execute(buildJsonObject { put("path", "/some/other") })
            assertTrue(result.isError)
            assertTrue(result.text.contains("/app/rest/"))
        }

        @Test
        fun `execute with path traversal returns error`() = runBlocking {
            val result = tool().execute(buildJsonObject { put("path", "/app/rest/../../admin") })
            assertTrue(result.isError)
            assertTrue(result.text.contains(".."))
        }

        @Test
        fun `execute with query string in path returns error mentioning query`() = runBlocking {
            val result = tool().execute(buildJsonObject { put("path", "/app/rest/buildQueue/id:1?a=b") })
            assertTrue(result.isError)
            assertTrue(result.text.contains("query"))
        }

        @Test
        fun `execute without client returns error about configuration`() = runBlocking {
            val result = tool(client = null).execute(buildJsonObject { put("path", "/app/rest/buildQueue/id:1") })
            assertTrue(result.isError)
            assertTrue(result.text.contains("not configured"))
        }
    }

    @Nested
    inner class PathNormalization {
        @Test
        fun `any rest path is accepted in brave mode (no allowlist)`() = runBlocking {
            val client = CapturingClient()
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildTypes/id:BT1")
            })
            assertFalse(result.isError)
            assertEquals("/app/rest/buildTypes/id:BT1", client.capturedPath)
        }

        @Test
        fun `trailing slash in path is normalized`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue/id:1/")
            })
            assertEquals("/app/rest/buildQueue/id:1", client.capturedPath)
        }
    }

    @Nested
    inner class ClientInvocation {
        @Test
        fun `passes path to client`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject { put("path", "/app/rest/buildQueue/id:1") })
            assertEquals("/app/rest/buildQueue/id:1", client.capturedPath)
        }

        @Test
        fun `passes query string through`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue/id:1")
                put("query", "fields=id")
            })
            assertEquals("fields=id", client.capturedQuery)
        }

        @Test
        fun `strips leading question mark from query`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue/id:1")
                put("query", "?fields=id")
            })
            assertEquals("fields=id", client.capturedQuery)
        }

        @Test
        fun `missing query produces empty string`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject { put("path", "/app/rest/buildQueue/id:1") })
            assertEquals("", client.capturedQuery)
        }
    }

    @Nested
    inner class ResponseFormatting {
        private fun respondingClient(body: String = "", statusCode: Int = 204) = object : RestApiClient {
            override suspend fun get(path: String, query: String) = throw UnsupportedOperationException()
            override suspend fun post(path: String, query: String, body: String) = throw UnsupportedOperationException()
            override suspend fun put(path: String, query: String, body: String) = throw UnsupportedOperationException()
            override suspend fun delete(path: String, query: String) = RestApiResponse(body = body, statusCode = statusCode)
        }

        @Test
        fun `response is structured json envelope`() = runBlocking {
            val result = tool(respondingClient()).execute(buildJsonObject { put("path", "/app/rest/buildQueue/id:1") })
            assertFalse(result.isError)
            val payload = parseResultJson(result.text)
            assertTrue(payload.containsKey("meta"))
            assertTrue(payload.containsKey("contentType"))
        }

        @Test
        fun `meta contains url and statusCode`() = runBlocking {
            val result = tool(respondingClient(statusCode = 204)).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue/id:1")
            })
            val meta = parseResultJson(result.text)["meta"]!!.jsonObject
            assertEquals("/app/rest/buildQueue/id:1", meta["url"]!!.jsonPrimitive.content)
            assertEquals(204, meta["statusCode"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Nested
    inner class ErrorHandling {
        @Test
        fun `generic exception surfaces message`() = runBlocking {
            val result = tool(throwingClient(RuntimeException("boom"))).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue/id:1")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("boom"))
        }

        @Test
        fun `RestApiException 404 gives path guidance`() = runBlocking {
            val result = tool(throwingClient(RestApiException(404, "Not Found", "no"))).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue/id:1")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("404"))
        }

        @Test
        fun `RestApiException 409 gives conflict guidance`() = runBlocking {
            val result = tool(throwingClient(RestApiException(409, "Conflict", "state"))).execute(buildJsonObject {
                put("path", "/app/rest/buildTypes/id:BT1")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("409"))
        }
    }
}
