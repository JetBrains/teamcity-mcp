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

class RestPostBraveToolTest {
    private val json = Json

    private fun settings(allowedPaths: Set<String>? = null): SettingsService {
        val s = mockk<SettingsService>()
        every { s.getRestPostAllowedPaths() } returns allowedPaths
        return s
    }

    private fun tool(
        client: RestApiClient? = CapturingClient(),
        settingsService: SettingsService = settings()
    ) = RestPostBraveTool(client, settingsService)

    private fun parseResultJson(text: String) = json.parseToJsonElement(text).jsonObject

    private class CapturingClient(
        private val response: RestApiResponse = RestApiResponse("{}")
    ) : RestApiClient {
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

        override suspend fun put(path: String, query: String, body: String): RestApiResponse =
            throw UnsupportedOperationException()

        override suspend fun delete(path: String, query: String): RestApiResponse =
            throw UnsupportedOperationException()
    }

    @Nested
    inner class Metadata {
        @Test
        fun `name collides with the safe rest post tool name`() {
            assertEquals(RestPostTool.NAME, tool().name)
            assertEquals("teamcity_rest_post", tool().name)
        }

        @Test
        fun `description makes clear no personal enforcement and default allow-all`() {
            val description = tool().description
            assertTrue(description.contains("\"personal\": true"))
            assertTrue(description.contains("teamcity.ai.mcp.rest.post.allowed.paths"))
            assertTrue(description.contains("all `/app/rest/...` POST paths are allowed"))
        }

        @Test
        fun `schema declares path body query and fields`() {
            val props = tool().inputSchema.properties!!
            assertTrue(props.containsKey("path"))
            assertTrue(props.containsKey("body"))
            assertTrue(props.containsKey("query"))
            assertTrue(props.containsKey("fields"))
        }

        @Test
        fun `path and body are required`() {
            val required = tool().inputSchema.required!!
            assertTrue(required.contains("path"))
            assertTrue(required.contains("body"))
        }
    }

    @Nested
    inner class PathAllowlist {
        @Test
        fun `null allowlist allows arbitrary rest paths`() = runBlocking {
            val client = CapturingClient()
            val result = tool(client, settings(allowedPaths = null)).execute(buildJsonObject {
                put("path", "/app/rest/projects")
                put("body", """{"name":"Demo"}""")
            })
            assertFalse(result.isError)
            assertEquals("/app/rest/projects", client.capturedPath)
        }

        @Test
        fun `non-null allowlist restricts paths`() = runBlocking {
            val result = tool(settingsService = settings(setOf("/app/rest/buildQueue"))).execute(buildJsonObject {
                put("path", "/app/rest/projects")
                put("body", """{"name":"Demo"}""")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("not allowed"))
        }

        @Test
        fun `non-null allowlist accepts matching path`() = runBlocking {
            val result = tool(settingsService = settings(setOf("/app/rest/buildQueue"))).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"BT1"}}""")
            })
            assertFalse(result.isError)
        }
    }

    @Nested
    inner class BodyPassthrough {
        @Test
        fun `does not force personal true`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"BT1"}}""")
            })
            val sentBody = json.parseToJsonElement(client.capturedBody!!).jsonObject
            assertFalse(sentBody.containsKey("personal"))
        }

        @Test
        fun `preserves personal false when user sets it explicitly`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"BT1"},"personal":false}""")
            })
            val sentBody = json.parseToJsonElement(client.capturedBody!!).jsonObject
            assertEquals("false", sentBody["personal"]!!.jsonPrimitive.content)
        }

        @Test
        fun `does not inject default comment for buildQueue`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"BT1"}}""")
            })
            val sentBody = json.parseToJsonElement(client.capturedBody!!).jsonObject
            assertFalse(sentBody.containsKey("comment"))
        }

        @Test
        fun `rejects json array body`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """[{"a":1}]""")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("JSON object"))
        }

        @Test
        fun `rejects non-json body`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", "not json")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("JSON"))
        }
    }

    @Nested
    inner class InputValidation {
        @Test
        fun `invalid path triggers error`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/app/rest/../admin")
                put("body", "{}")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains(".."))
        }

        @Test
        fun `missing body returns error`() = runBlocking {
            val result = tool().execute(buildJsonObject { put("path", "/app/rest/buildQueue") })
            assertTrue(result.isError)
            assertTrue(result.text.contains("body"))
        }

        @Test
        fun `null client returns configuration error`() = runBlocking {
            val result = tool(client = null).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", "{}")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("not configured"))
        }
    }

    @Nested
    inner class QueryAndFields {
        @Test
        fun `fields is added as query parameter`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"BT1"}}""")
                put("fields", "id,status")
            })
            assertEquals("fields=id,status", client.capturedQuery)
        }

        @Test
        fun `query and fields are combined`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"BT1"}}""")
                put("query", "moveToTop=true")
                put("fields", "id")
            })
            val captured = client.capturedQuery!!
            assertTrue(captured.contains("moveToTop=true"))
            assertTrue(captured.contains("fields=id"))
        }

        @Test
        fun `explicit fields in query is not duplicated by fields param`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"BT1"}}""")
                put("query", "fields=id,number")
                put("fields", "status")
            })
            val captured = client.capturedQuery!!
            assertEquals(1, "fields=".toRegex().findAll(captured).count())
        }
    }

    @Nested
    inner class ResponseFormatting {
        private fun respondingClient(responseBody: String = """{"id":123}""", statusCode: Int = 200) = object : RestApiClient {
            override suspend fun get(path: String, query: String) = throw UnsupportedOperationException()
            override suspend fun post(path: String, query: String, body: String) = RestApiResponse(body = responseBody, statusCode = statusCode)
            override suspend fun put(path: String, query: String, body: String) = throw UnsupportedOperationException()
            override suspend fun delete(path: String, query: String) = throw UnsupportedOperationException()
        }

        @Test
        fun `response is structured json envelope with meta`() = runBlocking {
            val result = tool(respondingClient()).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"BT1"}}""")
            })
            val payload = parseResultJson(result.text)
            assertTrue(payload.containsKey("meta"))
            assertTrue(payload.containsKey("body") || payload.containsKey("bodyText"))
        }

        @Test
        fun `no personal-enforcement note is present`() = runBlocking {
            val result = tool(respondingClient()).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"BT1"}}""")
            })
            assertFalse(result.text.contains("personal"))
        }
    }

    // -----------------------------------------------------------------------
    // Branch awareness — TW-99613
    // -----------------------------------------------------------------------

    @Nested
    inner class BranchAwareness {
        private fun respondingClient(responseBody: String = """{"id":123}""", statusCode: Int = 200) = object : RestApiClient {
            override suspend fun get(path: String, query: String) = throw UnsupportedOperationException()
            override suspend fun post(path: String, query: String, body: String) = RestApiResponse(body = responseBody, statusCode = statusCode)
            override suspend fun put(path: String, query: String, body: String) = throw UnsupportedOperationException()
            override suspend fun delete(path: String, query: String) = throw UnsupportedOperationException()
        }

        @Test
        fun `description mentions branchName for build triggers`() {
            assertTrue(tool().description.contains("branchName"))
        }

        @Test
        fun `appends branch warning note when posting to buildQueue without branchName`() = runBlocking {
            val result = tool(respondingClient()).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"BT1"}}""")
            })
            assertTrue(result.text.contains("branchName"),
                "expected branchName warning when posting to buildQueue without branchName")
        }

        @Test
        fun `no branch warning when branchName provided in body`() = runBlocking {
            val result = tool(respondingClient()).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"BT1"},"branchName":"feature-x"}""")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"].toString()
            assertFalse(notes.contains("branchName"),
                "expected no branchName warning when branchName is provided, got: $notes")
        }

        @Test
        fun `no branch warning when posting to non-buildQueue path`() = runBlocking {
            val result = tool(respondingClient()).execute(buildJsonObject {
                put("path", "/app/rest/projects")
                put("body", """{"name":"Demo"}""")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"].toString()
            assertFalse(notes.contains("branchName"))
        }
    }
}
