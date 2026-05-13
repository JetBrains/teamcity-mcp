package jetbrains.buildServer.ai.mcp.tools.rest

import jetbrains.buildServer.ai.mcp.SettingsService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import io.mockk.every
import io.mockk.mockk

class RestPostToolTest {
    private val json = Json

    private fun settings(
        allowedPaths: Set<String> = setOf("/app/rest/buildQueue")
    ): SettingsService {
        val s = mockk<SettingsService>()
        every { s.getRestPostAllowedPaths() } returns allowedPaths
        return s
    }

    private fun tool(
        client: RestApiClient? = succeedingClient(),
        settingsService: SettingsService = settings()
    ) = RestPostTool(client, settingsService)

    private fun parseResultJson(text: String) = json.parseToJsonElement(text).jsonObject

    private fun succeedingClient(
        responseBody: String = "{}",
        statusCode: Int = 200
    ) = object : RestApiClient {
        override suspend fun get(path: String, query: String) = throw UnsupportedOperationException()
        override suspend fun post(path: String, query: String, body: String) = RestApiResponse(responseBody, statusCode)
    }

    /** Client that captures arguments for assertions. */
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
    }

    // -----------------------------------------------------------------------
    // Metadata
    // -----------------------------------------------------------------------

    @Nested
    inner class Metadata {
        @Test
        fun `name is teamcity_rest_post`() {
            assertEquals("teamcity_rest_post", tool().name)
        }

        @Test
        fun `schema declares path body and fields properties`() {
            val props = tool().inputSchema.properties!!
            assertTrue(props.containsKey("path"))
            assertTrue(props.containsKey("body"))
            assertTrue(props.containsKey("fields"))
        }

        @Test
        fun `path and body are required parameters`() {
            val required = tool().inputSchema.required!!
            assertTrue(required.contains("path"))
            assertTrue(required.contains("body"))
            assertFalse(required.contains("fields"))
        }

        @Test
        fun `description mentions personal builds`() {
            assertTrue(tool().description.contains("personal"))
        }
    }

    // -----------------------------------------------------------------------
    // Input validation
    // -----------------------------------------------------------------------

    @Nested
    inner class InputValidation {
        @Test
        fun `execute without arguments returns error mentioning path`() = runBlocking {
            val result = tool().execute(null)
            assertTrue(result.isError)
            assertTrue(result.text.contains("path"))
        }

        @Test
        fun `execute without path returns error`() = runBlocking {
            val args = buildJsonObject {
                put("body", """{"buildType":{"id":"bt1"}}""")
            }
            val result = tool().execute(args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("path"))
        }

        @Test
        fun `execute with blank path returns error`() = runBlocking {
            val args = buildJsonObject {
                put("path", "  ")
                put("body", """{"buildType":{"id":"bt1"}}""")
            }
            val result = tool().execute(args)
            assertTrue(result.isError)
        }

        @Test
        fun `execute with path not starting with app rest returns error`() = runBlocking {
            val args = buildJsonObject {
                put("path", "/some/other/api")
                put("body", """{"buildType":{"id":"bt1"}}""")
            }
            val result = tool().execute(args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("/app/rest/"))
        }

        @Test
        fun `execute with path traversal returns error`() = runBlocking {
            val args = buildJsonObject {
                put("path", "/app/rest/../../admin/debug")
                put("body", """{"buildType":{"id":"bt1"}}""")
            }
            val result = tool().execute(args)
            assertTrue(result.isError)
            assertTrue(result.text.contains(".."))
        }

        @Test
        fun `execute with query string in path returns error`() = runBlocking {
            val args = buildJsonObject {
                put("path", "/app/rest/buildQueue?fields=count")
                put("body", """{"buildType":{"id":"bt1"}}""")
            }
            val result = tool().execute(args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("query"))
        }

        @Test
        fun `execute with fragment in path returns error`() = runBlocking {
            val args = buildJsonObject {
                put("path", "/app/rest/buildQueue#section")
                put("body", """{"buildType":{"id":"bt1"}}""")
            }
            val result = tool().execute(args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("#"))
        }

        @Test
        fun `execute without body returns error`() = runBlocking {
            val args = buildJsonObject {
                put("path", "/app/rest/buildQueue")
            }
            val result = tool().execute(args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("body"))
        }

        @Test
        fun `execute with blank body returns error`() = runBlocking {
            val args = buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", "  ")
            }
            val result = tool().execute(args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("body"))
        }

        @Test
        fun `execute with non-json body returns error`() = runBlocking {
            val args = buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", "not json at all")
            }
            val result = tool().execute(args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("JSON"))
        }

        @Test
        fun `execute without client returns error about configuration`() = runBlocking {
            val args = buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            }
            val result = tool(client = null).execute(args)
            assertTrue(result.isError)
            assertTrue(result.text.contains("not configured"))
        }
    }

    // -----------------------------------------------------------------------
    // Path allowlist
    // -----------------------------------------------------------------------

    @Nested
    inner class PathAllowlist {
        @Test
        fun `allowed path succeeds`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            assertFalse(result.isError)
        }

        @Test
        fun `disallowed path returns error`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/app/rest/projects")
                put("body", """{"name":"test"}""")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("not allowed"))
        }

        @Test
        fun `prefix attack is rejected - buildQueueExtra`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/app/rest/buildQueueExtra")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("not allowed"))
        }

        @Test
        fun `extension attack is rejected - buildQueue slash sub`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/app/rest/buildQueue/something")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("not allowed"))
        }

        @Test
        fun `empty allowlist rejects everything`() = runBlocking {
            val result = tool(settingsService = settings(emptySet())).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("not allowed"))
        }

        @Test
        fun `multiple allowed paths work`() = runBlocking {
            val s = settings(setOf("/app/rest/buildQueue", "/app/rest/projects"))
            val result = tool(settingsService = s).execute(buildJsonObject {
                put("path", "/app/rest/projects")
                put("body", """{"name":"test"}""")
            })
            assertFalse(result.isError)
        }

        @Test
        fun `trailing slash is normalized - path with trailing slash`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/app/rest/buildQueue/")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            assertFalse(result.isError)
        }
    }

    // -----------------------------------------------------------------------
    // Personal build enforcement
    // -----------------------------------------------------------------------

    @Nested
    inner class PersonalEnforcement {
        @Test
        fun `injects personal true when not present`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            val sentBody = json.parseToJsonElement(client.capturedBody!!).jsonObject
            assertEquals("true", sentBody["personal"]!!.jsonPrimitive.content)
        }

        @Test
        fun `overwrites personal false to true`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"},"personal":false}""")
            })
            val sentBody = json.parseToJsonElement(client.capturedBody!!).jsonObject
            assertEquals("true", sentBody["personal"]!!.jsonPrimitive.content)
        }

        @Test
        fun `preserves other fields alongside personal`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"},"branchName":"main","comment":{"text":"test"}}""")
            })
            val sentBody = json.parseToJsonElement(client.capturedBody!!).jsonObject
            assertEquals("true", sentBody["personal"]!!.jsonPrimitive.content)
            assertTrue(sentBody.containsKey("buildType"))
            assertTrue(sentBody.containsKey("branchName"))
            assertTrue(sentBody.containsKey("comment"))
        }

        @Test
        fun `adds default comment when none provided for buildQueue`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            val sentBody = json.parseToJsonElement(client.capturedBody!!).jsonObject
            val comment = sentBody["comment"]!!.jsonObject
            assertEquals("Triggered via MCP", comment["text"]!!.jsonPrimitive.content)
        }

        @Test
        fun `preserves user-provided comment for buildQueue`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"},"comment":{"text":"my reason"}}""")
            })
            val sentBody = json.parseToJsonElement(client.capturedBody!!).jsonObject
            val comment = sentBody["comment"]!!.jsonObject
            assertEquals("my reason", comment["text"]!!.jsonPrimitive.content)
        }

        @Test
        fun `does not add default comment for non-buildQueue path`() = runBlocking {
            val client = CapturingClient()
            val s = settings(setOf("/app/rest/buildQueue", "/app/rest/projects"))
            tool(client, s).execute(buildJsonObject {
                put("path", "/app/rest/projects")
                put("body", """{"name":"test"}""")
            })
            val sentBody = json.parseToJsonElement(client.capturedBody!!).jsonObject
            assertFalse(sentBody.containsKey("comment"))
        }

        @Test
        fun `rejects json array body`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """[{"buildType":{"id":"bt1"}}]""")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("JSON object"))
        }

        @Test
        fun `rejects json primitive body`() = runBlocking {
            val result = tool().execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """"just a string"""")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("JSON object"))
        }
    }

    // -----------------------------------------------------------------------
    // Client invocation
    // -----------------------------------------------------------------------

    @Nested
    inner class ClientInvocation {
        @Test
        fun `passes path to client`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            assertEquals("/app/rest/buildQueue", client.capturedPath)
        }

        @Test
        fun `fields parameter becomes query string`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
                put("fields", "id,number,status")
            })
            assertEquals("fields=id,number,status", client.capturedQuery)
        }

        @Test
        fun `empty fields produces empty query`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            assertEquals("", client.capturedQuery)
        }

        @Test
        fun `trims whitespace from path and fields`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "  /app/rest/buildQueue  ")
                put("body", """{"buildType":{"id":"bt1"}}""")
                put("fields", "  id,number  ")
            })
            assertEquals("/app/rest/buildQueue", client.capturedPath)
            assertEquals("fields=id,number", client.capturedQuery)
        }
    }

    // -----------------------------------------------------------------------
    // Response formatting
    // -----------------------------------------------------------------------

    @Nested
    inner class ResponseFormatting {
        @Test
        fun `response is structured json envelope`() = runBlocking {
            val result = tool(succeedingClient("""{"id":123,"personal":true}""")).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            assertFalse(result.isError)
            val payload = parseResultJson(result.text)
            assertTrue(payload.containsKey("meta"))
            assertTrue(payload.containsKey("contentType"))
            assertTrue(payload.containsKey("body"))
        }

        @Test
        fun `meta contains url and statusCode`() = runBlocking {
            val result = tool(succeedingClient("""{"id":123}""")).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
                put("fields", "id,status")
            })
            val meta = parseResultJson(result.text)["meta"]!!.jsonObject
            assertEquals("/app/rest/buildQueue?fields=id,status", meta["url"]!!.jsonPrimitive.content)
            assertEquals(200, meta["statusCode"]!!.jsonPrimitive.content.toInt())
        }

        @Test
        fun `json body is parsed into body field`() = runBlocking {
            val result = tool(succeedingClient("""{"id":123,"status":"SUCCESS"}""")).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            val payload = parseResultJson(result.text)
            assertEquals("application/json", payload["contentType"]!!.jsonPrimitive.content)
            val body = payload["body"]!!.jsonObject
            assertEquals(123, body["id"]!!.jsonPrimitive.content.toInt())
        }

        @Test
        fun `non-json response falls back to bodyText`() = runBlocking {
            val result = tool(succeedingClient("not json")).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            val payload = parseResultJson(result.text)
            assertEquals("text/plain", payload["contentType"]!!.jsonPrimitive.content)
            assertEquals("not json", payload["bodyText"]!!.jsonPrimitive.content)
        }

        @Test
        fun `notes array includes personal enforcement note`() = runBlocking {
            val result = tool(succeedingClient("""{"id":123}""")).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray
            assertTrue(notes.any { it.jsonPrimitive.content.contains("personal") })
        }

        @Test
        fun `non-200 status code is reflected in meta`() = runBlocking {
            val result = tool(succeedingClient("""{"message":"error"}""", 409)).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            val meta = parseResultJson(result.text)["meta"]!!.jsonObject
            assertEquals(409, meta["statusCode"]!!.jsonPrimitive.content.toInt())
        }
    }

    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

    @Nested
    inner class ErrorHandling {
        private fun throwingClient(e: Exception) = object : RestApiClient {
            override suspend fun get(path: String, query: String): RestApiResponse = throw e
            override suspend fun post(path: String, query: String, body: String): RestApiResponse = throw e
        }

        @Test
        fun `client exception returns error result with message`() = runBlocking {
            val client = throwingClient(RuntimeException("Connection refused"))
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("Connection refused"))
        }

        @Test
        fun `RestApiException 400 gives query guidance`() = runBlocking {
            val client = throwingClient(RestApiException(400, "Bad Request", "Bad request body"))
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("400"))
        }

        @Test
        fun `RestApiException 403 gives permission guidance`() = runBlocking {
            val client = throwingClient(RestApiException(403, "Forbidden", "Access denied"))
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("403"))
            assertTrue(result.text.contains("permission") || result.text.contains("access"))
        }

        @Test
        fun `RestApiException 404 gives path guidance`() = runBlocking {
            val client = throwingClient(RestApiException(404, "Not Found", "Nothing here"))
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("404"))
        }

        @Test
        fun `RestApiException 500 gives server error guidance`() = runBlocking {
            val client = throwingClient(RestApiException(500, "Internal Server Error", "Oops"))
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            assertTrue(result.isError)
            assertTrue(result.text.contains("500"))
        }
    }

    // -----------------------------------------------------------------------
    // Branch awareness — TW-99613
    // -----------------------------------------------------------------------

    @Nested
    inner class BranchAwareness {
        @Test
        fun `description mentions branchName for build triggers`() {
            val description = tool().description
            assertTrue(description.contains("branchName"),
                "description should mention branchName so the LLM is reminded to include it")
        }

        @Test
        fun `description tells the model to determine the target branch first`() {
            val description = tool().description.lowercase()
            assertTrue(description.contains("branch"), "description should foreground branch handling")
            assertTrue(description.contains("default branch"), "description should explain the default-branch fallback")
        }

        @Test
        fun `appends branch warning note when posting to buildQueue without branchName`() = runBlocking {
            val result = tool(succeedingClient("""{"id":1}""")).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"}}""")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray
            assertTrue(notes.any { it.jsonPrimitive.content.contains("branchName") },
                "expected branchName warning in notes, got: $notes")
        }

        @Test
        fun `no branch warning when branchName provided in body`() = runBlocking {
            val result = tool(succeedingClient("""{"id":1}""")).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("body", """{"buildType":{"id":"bt1"},"branchName":"feature-x"}""")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            assertFalse(notes.contains("branchName"),
                "expected no branchName warning when branchName is provided, got: $notes")
        }

        @Test
        fun `no branch warning when posting to non-buildQueue path`() = runBlocking {
            val s = settings(setOf("/app/rest/buildQueue", "/app/rest/projects"))
            val result = tool(succeedingClient("""{"id":1}"""), s).execute(buildJsonObject {
                put("path", "/app/rest/projects")
                put("body", """{"name":"test"}""")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            assertFalse(notes.contains("branchName"))
        }
    }
}
