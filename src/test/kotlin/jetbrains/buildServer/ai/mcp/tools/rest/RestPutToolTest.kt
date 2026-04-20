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

class RestPutToolTest {
    private val json = Json

    private fun settings(braveModeEnabled: Boolean = true): SettingsService {
        val s = mockk<SettingsService>()
        every { s.isBraveModeEnabled() } returns braveModeEnabled
        return s
    }

    private fun tool(
        client: RestApiClient? = CapturingClient(),
        settingsService: SettingsService = settings()
    ) = RestPutTool(client, settingsService)

    private fun parseResultJson(text: String) = json.parseToJsonElement(text).jsonObject

    private class CapturingClient(
        private val response: RestApiResponse = RestApiResponse("{}")
    ) : RestApiClient {
        var capturedPath: String? = null
        var capturedQuery: String? = null
        var capturedBody: String? = null

        override suspend fun get(path: String, query: String): RestApiResponse =
            throw UnsupportedOperationException()

        override suspend fun post(path: String, query: String, body: String): RestApiResponse =
            throw UnsupportedOperationException()

        override suspend fun put(path: String, query: String, body: String): RestApiResponse {
            capturedPath = path
            capturedQuery = query
            capturedBody = body
            return response
        }

        override suspend fun delete(path: String, query: String): RestApiResponse =
            throw UnsupportedOperationException()
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
        fun `name is teamcity_rest_put`() {
            assertEquals("teamcity_rest_put", tool().name)
        }

        @Test
        fun `schema declares path body and query properties`() {
            val props = tool().inputSchema.properties!!
            assertTrue(props.containsKey("path"))
            assertTrue(props.containsKey("body"))
            assertTrue(props.containsKey("query"))
        }

        @Test
        fun `path and body are required parameters but query is not`() {
            val required = tool().inputSchema.required!!
            assertTrue(required.contains("path"))
            assertTrue(required.contains("body"))
            assertFalse(required.contains("query"))
        }

        @Test
        fun `description describes the tool purpose`() {
            val description = tool().description
            assertTrue(description.contains("PUT"))
            assertTrue(description.contains("TeamCity REST API"))
        }
    }

    @Nested
    inner class InputValidation {
        @Test
        fun `brave mode must be enabled`() = runBlocking {
            val result = tool(settingsService = settings(braveModeEnabled = false)).execute(buildJsonObject {
                put("path", "/app/rest/builds/id:1/comment")
                put("body", "hello")
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
            val result = tool().execute(buildJsonObject {
                put("path", "  ")
                put("body", "true")
            })
            assertTrue(result.isError)
        }

        @Test
        fun `execute with path not starting with app rest returns error`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/some/other/api")
                put("body", "true")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("/app/rest/"))
        }

        @Test
        fun `execute with path traversal returns error`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/app/rest/../../admin")
                put("body", "true")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains(".."))
        }

        @Test
        fun `execute with query string in path returns error mentioning query`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/app/rest/builds/id:1/comment?fields=x")
                put("body", "text")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("query"))
        }

        @Test
        fun `execute with fragment in path returns error`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/app/rest/builds/id:1#frag")
                put("body", "text")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("#"))
        }

        @Test
        fun `execute without body returns error`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/app/rest/builds/id:1/comment")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("body"))
        }

        @Test
        fun `execute with blank body returns error`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/app/rest/builds/id:1/comment")
                put("body", "   ")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("body"))
        }

        @Test
        fun `execute without client returns error about configuration`() = runBlocking {
            val result = tool(client = null).execute(buildJsonObject {
                put("path", "/app/rest/builds/id:1/comment")
                put("body", "text")
            })
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
                put("path", "/app/rest/buildTypes/id:BT1/paused")
                put("body", "true")
            })
            assertFalse(result.isError)
            assertEquals("/app/rest/buildTypes/id:BT1/paused", client.capturedPath)
        }

        @Test
        fun `trailing slash in path is normalized`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds/id:1/pin/")
                put("body", "true")
            })
            assertEquals("/app/rest/builds/id:1/pin", client.capturedPath)
        }
    }

    @Nested
    inner class ClientInvocation {
        @Test
        fun `passes body unchanged to client`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds/id:1/comment")
                put("body", "release candidate")
            })
            assertEquals("release candidate", client.capturedBody)
        }

        @Test
        fun `passes query string through`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildTypes/id:BT1/paused")
                put("body", "true")
                put("query", "fields=id")
            })
            assertEquals("fields=id", client.capturedQuery)
        }

        @Test
        fun `strips leading question mark from query`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildTypes/id:BT1/paused")
                put("body", "true")
                put("query", "?fields=id")
            })
            assertEquals("fields=id", client.capturedQuery)
        }

        @Test
        fun `missing query produces empty string`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds/id:1/comment")
                put("body", "x")
            })
            assertEquals("", client.capturedQuery)
        }
    }

    @Nested
    inner class ResponseFormatting {
        private fun respondingClient(responseBody: String = "{}", statusCode: Int = 200) = object : RestApiClient {
            override suspend fun get(path: String, query: String) = throw UnsupportedOperationException()
            override suspend fun post(path: String, query: String, body: String) = throw UnsupportedOperationException()
            override suspend fun put(path: String, query: String, body: String) = RestApiResponse(body = responseBody, statusCode = statusCode)
            override suspend fun delete(path: String, query: String) = throw UnsupportedOperationException()
        }

        @Test
        fun `response is structured json envelope`() = runBlocking {
            val result = tool(respondingClient("""{"id":1}""")).execute(buildJsonObject {
                put("path", "/app/rest/builds/id:1/comment")
                put("body", "x")
            })
            assertFalse(result.isError)
            val payload = parseResultJson(result.text)
            assertTrue(payload.containsKey("meta"))
            assertTrue(payload.containsKey("contentType"))
        }

        @Test
        fun `meta contains url and statusCode`() = runBlocking {
            val result = tool(respondingClient("""{"id":1}""", 204)).execute(buildJsonObject {
                put("path", "/app/rest/builds/id:1/comment")
                put("body", "x")
                put("query", "fields=id")
            })
            val meta = parseResultJson(result.text)["meta"]!!.jsonObject
            assertEquals("/app/rest/builds/id:1/comment?fields=id", meta["url"]!!.jsonPrimitive.content)
            assertEquals(204, meta["statusCode"]!!.jsonPrimitive.content.toInt())
        }

        @Test
        fun `plain text body falls back to bodyText`() = runBlocking {
            val result = tool(respondingClient("not valid json at all")).execute(buildJsonObject {
                put("path", "/app/rest/builds/id:1/comment")
                put("body", "release candidate")
            })
            val payload = parseResultJson(result.text)
            assertEquals("text/plain", payload["contentType"]!!.jsonPrimitive.content)
        }
    }

    @Nested
    inner class ErrorHandling {
        @Test
        fun `generic exception surfaces message`() = runBlocking {
            val result = tool(throwingClient(RuntimeException("boom"))).execute(buildJsonObject {
                put("path", "/app/rest/builds/id:1/comment")
                put("body", "x")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("boom"))
        }

        @Test
        fun `RestApiException 403 gives permission guidance`() = runBlocking {
            val result = tool(throwingClient(RestApiException(403, "Forbidden", "no"))).execute(buildJsonObject {
                put("path", "/app/rest/builds/id:1/comment")
                put("body", "x")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("403"))
            assertTrue(result.text.contains("permission") || result.text.contains("Access denied"))
        }

        @Test
        fun `RestApiException 409 gives conflict guidance`() = runBlocking {
            val result = tool(throwingClient(RestApiException(409, "Conflict", "state"))).execute(buildJsonObject {
                put("path", "/app/rest/buildTypes/id:BT1/paused")
                put("body", "true")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("409"))
        }
    }
}
