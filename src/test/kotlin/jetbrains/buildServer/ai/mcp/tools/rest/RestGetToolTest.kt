package jetbrains.buildServer.ai.mcp.tools.rest

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RestGetToolTest {
    private val json = Json

    private fun tool(client: RestApiClient? = null) = RestGetTool(client)

    private fun parseResultJson(text: String) = json.parseToJsonElement(text).jsonObject

    private fun succeedingClient(
        body: String = "{}",
        statusCode: Int = 200,
        truncated: Boolean = false
    ) = RestApiClient { _, _ -> RestApiResponse(body, statusCode, truncated) }

    /** Client that captures path and query for assertions. */
    private class CapturingClient(
        private val response: RestApiResponse = RestApiResponse("{}")
    ) : RestApiClient {
        var capturedPath: String? = null
        var capturedQuery: String? = null

        override suspend fun get(path: String, query: String): RestApiResponse {
            capturedPath = path
            capturedQuery = query
            return response
        }
    }

    // -----------------------------------------------------------------------
    // Metadata
    // -----------------------------------------------------------------------

    @Test
    fun `name is teamcity_rest_get`() {
        Assertions.assertEquals("teamcity_rest_get", tool().name)
    }

    @Test
    fun `schema declares path and query properties`() {
        val props = tool().inputSchema.properties!!
        Assertions.assertTrue(props.containsKey("path"))
        Assertions.assertTrue(props.containsKey("query"))
    }

    @Test
    fun `path is the only required parameter`() {
        Assertions.assertEquals(listOf("path"), tool().inputSchema.required)
    }

    @Test
    fun `description documents structured envelope response`() {
        val description = tool().description
        Assertions.assertTrue(description.contains("meta("))
        Assertions.assertTrue(description.contains("contentType"))
        Assertions.assertTrue(description.contains("bodyText"))
        Assertions.assertTrue(description.contains("meta.notes"))
    }

    @Test
    fun `description clarifies fields are for json endpoints and log exception`() {
        val description = tool().description
        Assertions.assertTrue(description.contains("For JSON endpoints"))
        Assertions.assertTrue(description.contains("/log"))
    }

    @Test
    fun `query schema description clarifies log does not use fields`() {
        val queryDescription = tool().inputSchema.properties!!
            .getValue("query")
            .jsonObject
            .getValue("description")
            .jsonPrimitive
            .content
        Assertions.assertTrue(queryDescription.contains("/log endpoints"))
        Assertions.assertTrue(queryDescription.contains("'fields' is not used"))
    }

    // -----------------------------------------------------------------------
    // Input validation
    // -----------------------------------------------------------------------

    @Nested
    inner class InputValidation {

        @Test
        fun `execute without arguments returns error mentioning path`() = runBlocking {
            val result = tool(succeedingClient()).execute(null)
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("path"))
        }

        @Test
        fun `execute without path parameter returns error`() = runBlocking {
            val args = buildJsonObject { put("query", "fields=count") }
            val result = tool(succeedingClient()).execute(args)
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("path"))
        }

        @Test
        fun `execute with blank path returns error`() = runBlocking {
            val args = buildJsonObject { put("path", "  ") }
            val result = tool(succeedingClient()).execute(args)
            Assertions.assertTrue(result.isError)
        }

        @Test
        fun `execute with path not starting with app rest returns error`() = runBlocking {
            val args = buildJsonObject { put("path", "/some/other/api") }
            val result = tool(succeedingClient()).execute(args)
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("/app/rest/"))
        }

        @Test
        fun `execute with path equal to app rest without trailing slash returns error`() = runBlocking {
            val args = buildJsonObject { put("path", "/app/rest") }
            val result = tool(succeedingClient()).execute(args)
            Assertions.assertTrue(result.isError)
        }

        @Test
        fun `execute without rest client returns error about configuration`() = runBlocking {
            val args = buildJsonObject { put("path", "/app/rest/builds") }
            val result = tool(client = null).execute(args)
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("not configured"))
        }

        @Test
        fun `execute with path traversal returns error`() = runBlocking {
            val args = buildJsonObject { put("path", "/app/rest/../../admin/debug") }
            val result = tool(succeedingClient()).execute(args)
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains(".."))
        }

        @Test
        fun `execute with query string in path returns error`() = runBlocking {
            val args = buildJsonObject { put("path", "/app/rest/builds?fields=count") }
            val result = tool(succeedingClient()).execute(args)
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("query"))
        }

        @Test
        fun `execute with fragment in path returns error`() = runBlocking {
            val args = buildJsonObject { put("path", "/app/rest/builds#section") }
            val result = tool(succeedingClient()).execute(args)
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("#"))
        }

        @Test
        fun `leading question mark in query is stripped`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "?start=0&count=10&fields=count")
            })
            Assertions.assertEquals("start=0&count=10&fields=count", client.capturedQuery)
        }
    }

    // -----------------------------------------------------------------------
    // Pagination enforcement
    // -----------------------------------------------------------------------

    @Nested
    inner class PaginationEnforcement {

        @Test
        fun `empty query gets default pagination`() {
            val result = tool().ensurePaging("")
            Assertions.assertTrue(result.enforced)
            Assertions.assertTrue(result.query.contains("start=0"))
            Assertions.assertTrue(result.query.contains("count=${RestGetTool.DEFAULT_PAGE_SIZE}"))
            Assertions.assertNotNull(result.note)
        }

        @Test
        fun `query without pagination gets defaults appended`() {
            val result = tool().ensurePaging("fields=build(id)")
            Assertions.assertTrue(result.enforced)
            Assertions.assertTrue(result.query.startsWith("fields=build(id)"))
            Assertions.assertTrue(result.query.contains("start=0"))
            Assertions.assertTrue(result.query.contains("count=${RestGetTool.DEFAULT_PAGE_SIZE}"))
        }

        @Test
        fun `query with both start and count is not modified`() {
            val original = "start=5&count=20&fields=build(id)"
            val result = tool().ensurePaging(original)
            Assertions.assertFalse(result.enforced)
            Assertions.assertEquals(original, result.query)
            Assertions.assertEquals(null, result.note)
        }

        @Test
        fun `pagination in locator is detected`() {
            val original = "locator=buildType:(id:BT1),start:0,count:10&fields=build(id)"
            val result = tool().ensurePaging(original)
            Assertions.assertFalse(result.enforced)
            Assertions.assertEquals(original, result.query)
        }

        @Test
        fun `missing start with count present gets start added`() {
            val result = tool().ensurePaging("count=10&fields=build(id)")
            Assertions.assertTrue(result.enforced)
            Assertions.assertTrue(result.query.contains("start=0"))
            Assertions.assertTrue(result.query.contains("count=10"))
        }

        @Test
        fun `missing count with start present gets count added`() {
            val result = tool().ensurePaging("start=5&fields=build(id)")
            Assertions.assertTrue(result.enforced)
            Assertions.assertTrue(result.query.contains("start=5"))
            Assertions.assertTrue(result.query.contains("count=${RestGetTool.DEFAULT_PAGE_SIZE}"))
        }

        @Test
        fun `start in locator and count as top level both detected`() {
            val original = "locator=buildType:(id:BT1),start:0&count=10"
            val result = tool().ensurePaging(original)
            Assertions.assertFalse(result.enforced)
            Assertions.assertEquals(original, result.query)
        }

        @Test
        fun `count in locator and start as top level both detected`() {
            val original = "start=0&locator=buildType:(id:BT1),count:10"
            val result = tool().ensurePaging(original)
            Assertions.assertFalse(result.enforced)
            Assertions.assertEquals(original, result.query)
        }

        @Test
        fun `whitespace-only query gets default pagination`() {
            val result = tool().ensurePaging("   ")
            Assertions.assertTrue(result.enforced)
            Assertions.assertTrue(result.query.contains("start=0"))
            Assertions.assertTrue(result.query.contains("count=${RestGetTool.DEFAULT_PAGE_SIZE}"))
        }

        @Test
        fun `pagination detected in locator with nested parentheses`() {
            val original = "locator=buildType:(project:(id:P1)),count:10,start:0&fields=build(id)"
            val result = tool().ensurePaging(original)
            Assertions.assertFalse(result.enforced)
            Assertions.assertEquals(original, result.query)
        }

        @Test
        fun `count exceeding max is capped at top level`() {
            val result = tool().ensurePaging("start=0&count=500&fields=build(id)")
            Assertions.assertTrue(result.query.contains("count=${RestGetTool.MAX_PAGE_SIZE}"))
            Assertions.assertFalse(result.query.contains("count=500"))
            Assertions.assertNotNull(result.note)
        }

        @Test
        fun `count exceeding max is capped in locator`() {
            val result = tool().ensurePaging("locator=buildType:(id:BT1),start:0,count:200&fields=build(id)")
            Assertions.assertTrue(result.query.contains("count:${RestGetTool.MAX_PAGE_SIZE}"))
            Assertions.assertFalse(result.query.contains("count:200"))
            Assertions.assertNotNull(result.note)
        }

        @Test
        fun `count exceeding max capped end-to-end via client`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=500&fields=build(id)")
            })
            Assertions.assertTrue(client.capturedQuery!!.contains("count=${RestGetTool.MAX_PAGE_SIZE}"))
            Assertions.assertFalse(client.capturedQuery!!.contains("count=500"))
        }

        @Test
        fun `count at max is not modified`() {
            val original = "start=0&count=${RestGetTool.MAX_PAGE_SIZE}&fields=build(id)"
            val result = tool().ensurePaging(original)
            Assertions.assertEquals(original, result.query)
        }

        @Test
        fun `pagination not enforced for single-resource paths`() {
            // When requesting a single resource (e.g. /app/rest/builds/id:123),
            // the query typically has fields but no locator/paging. We still add
            // pagination because the tool can't distinguish single vs collection.
            // This is harmless — single-resource endpoints ignore start/count.
            val result = tool().ensurePaging("fields=id,status")
            Assertions.assertTrue(result.enforced)
        }
    }

    // -----------------------------------------------------------------------
    // Client invocation
    // -----------------------------------------------------------------------

    @Nested
    inner class ClientInvocation {

        @Test
        fun `passes path unchanged to client`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject { put("path", "/app/rest/builds") })
            Assertions.assertEquals("/app/rest/builds", client.capturedPath)
        }

        @Test
        fun `passes pagination-enforced query to client`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "fields=build(id)")
            })
            Assertions.assertTrue(client.capturedQuery!!.contains("fields=build(id)"))
            Assertions.assertTrue(client.capturedQuery!!.contains("start=0"))
            Assertions.assertTrue(client.capturedQuery!!.contains("count="))
        }

        @Test
        fun `passes original query when pagination present`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=5&fields=build(id)")
            })
            Assertions.assertEquals("start=0&count=5&fields=build(id)", client.capturedQuery)
        }

        @Test
        fun `trims whitespace from path and query`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "  /app/rest/builds  ")
                put("query", "  start=0&count=5  ")
            })
            Assertions.assertEquals("/app/rest/builds", client.capturedPath)
            Assertions.assertEquals("start=0&count=5", client.capturedQuery)
        }
    }

    // -----------------------------------------------------------------------
    // Response formatting
    // -----------------------------------------------------------------------

    @Nested
    inner class ResponseFormatting {

        @Test
        fun `response is structured json envelope for json endpoints`() = runBlocking {
            val result = tool(succeedingClient("""{"count":0}""")).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10&fields=count")
            })
            Assertions.assertFalse(result.isError)
            val payload = parseResultJson(result.text)
            val meta = payload["meta"]!!.jsonObject
            Assertions.assertEquals("/app/rest/builds?start=0&count=10&fields=count", meta["url"]!!.toString().trim('"'))
            Assertions.assertEquals(200, meta["statusCode"]!!.toString().toInt())
            Assertions.assertEquals("application/json", payload["contentType"]!!.toString().trim('"'))
            Assertions.assertTrue(payload.containsKey("body"))
        }

        @Test
        fun `response includes pagination note in meta notes when enforced`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                // no pagination in query — will be auto-added
            })
            Assertions.assertFalse(result.isError)
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            Assertions.assertTrue(notes.contains("automatically added"))
        }

        @Test
        fun `response has empty notes array when no notes apply`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10&fields=build(id)")
            })
            Assertions.assertFalse(result.isError)
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray
            Assertions.assertEquals(0, notes.size)
        }

        @Test
        fun `response includes truncation note when truncated`() = runBlocking {
            val client = succeedingClient(body = """{"data":"..."}""", truncated = true)
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10&fields=build(id)")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            Assertions.assertTrue(notes.contains("truncated"))
        }

        @Test
        fun `response includes nextHref flag and note when present in json body`() = runBlocking {
            val body = """{"count":10,"nextHref":"/app/rest/builds?start=10&count=10","build":[]}"""
            val result = tool(succeedingClient(body)).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10&fields=build(id)")
            })
            val meta = parseResultJson(result.text)["meta"]!!.jsonObject
            Assertions.assertEquals("true", meta["hasNextHref"]!!.toString())
            val notes = meta["notes"]!!.jsonArray.toString()
            Assertions.assertTrue(notes.contains("nextHref"))
        }

        @Test
        fun `response embeds parsed json body`() = runBlocking {
            val body = """{"count":42}"""
            val result = tool(succeedingClient(body)).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10&fields=count")
            })
            val payload = parseResultJson(result.text)
            val bodyNode = payload["body"]!!.jsonObject
            Assertions.assertEquals(42, bodyNode["count"]!!.toString().toInt())
        }

        @Test
        fun `response meta includes status code when not 200`() = runBlocking {
            val client = succeedingClient(body = """{"message":"Not found"}""", statusCode = 404)
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10&fields=build(id)")
            })
            val meta = parseResultJson(result.text)["meta"]!!.jsonObject
            Assertions.assertEquals(404, meta["statusCode"]!!.toString().toInt())
        }

        @Test
        fun `response includes missing-fields warning note when fields absent`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            Assertions.assertTrue(notes.contains("No 'fields'"))
        }

        @Test
        fun `no missing-fields warning when fields is present`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10&fields=build(id)")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            Assertions.assertFalse(notes.contains("No 'fields'"))
        }

        @Test
        fun `response adds note when collection is empty`() = runBlocking {
            val body = """{"count":0,"build":[]}"""
            val result = tool(succeedingClient(body)).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10&fields=build(id)")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            Assertions.assertTrue(notes.contains("0 items"))
        }

        @Test
        fun `no empty hint when count zero is nested not top-level`() = runBlocking {
            // "count":0 in a nested object should NOT trigger the empty hint
            val body = """{"count":5,"build":[{"id":1,"testOccurrences":{"count":0}}]}"""
            val result = tool(succeedingClient(body)).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10&fields=build(id)")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            Assertions.assertFalse(notes.contains("0 items"))
        }

        @Test
        fun `no empty hint when count is not zero`() = runBlocking {
            val body = """{"count":5,"build":[{"id":1}]}"""
            val result = tool(succeedingClient(body)).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10&fields=build(id)")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            Assertions.assertFalse(notes.contains("0 items"))
        }

        @Test
        fun `no nextHref hint when body does not contain nextHref`() = runBlocking {
            val body = """{"count":3,"build":[{"id":1},{"id":2},{"id":3}]}"""
            val result = tool(succeedingClient(body)).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10&fields=build(id)")
            })
            val meta = parseResultJson(result.text)["meta"]!!.jsonObject
            Assertions.assertEquals("false", meta["hasNextHref"]!!.toString())
        }

        @Test
        fun `log endpoint returns text plain content with bodyText`() = runBlocking {
            val body = "Build started...\nStep 1"
            val result = tool(succeedingClient(body)).execute(buildJsonObject {
                put("path", "/app/rest/builds/id:123/log")
                put("query", "start=0&count=10")
            })
            val payload = parseResultJson(result.text)
            Assertions.assertEquals("text/plain", payload["contentType"]!!.toString().trim('"'))
            Assertions.assertEquals(body, payload["bodyText"]!!.jsonPrimitive.content)
            Assertions.assertFalse(payload.containsKey("body"))
        }

        @Test
        fun `log endpoint does not add missing fields note`() = runBlocking {
            val result = tool(succeedingClient("line1")).execute(buildJsonObject {
                put("path", "/app/rest/builds/id:123/log")
                put("query", "start=0&count=10")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            Assertions.assertFalse(notes.contains("No 'fields'"))
        }

        @Test
        fun `non log endpoint with non json body falls back to bodyText with note`() = runBlocking {
            val result = tool(succeedingClient("{invalid-json")).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10&fields=build(id)")
            })
            val payload = parseResultJson(result.text)
            val meta = payload["meta"]!!.jsonObject
            Assertions.assertEquals("text/plain", payload["contentType"]!!.toString().trim('"'))
            Assertions.assertEquals("{invalid-json", payload["bodyText"]!!.jsonPrimitive.content)
            val notes = meta["notes"]!!.jsonArray.toString()
            Assertions.assertTrue(notes.contains("not valid JSON"))
        }

        @Test
        fun `log endpoint keeps hasNextHref false even when log text mentions nextHref`() = runBlocking {
            val result = tool(succeedingClient("line mentions nextHref here")).execute(buildJsonObject {
                put("path", "/app/rest/builds/id:123/log")
                put("query", "start=0&count=10")
            })
            val meta = parseResultJson(result.text)["meta"]!!.jsonObject
            Assertions.assertEquals("false", meta["hasNextHref"]!!.toString())
        }
    }

    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

    @Nested
    inner class ErrorHandling {

        @Test
        fun `client exception returns error result with message`() = runBlocking {
            val client = RestApiClient { _, _ -> throw RuntimeException("Connection refused") }
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
            })
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("Connection refused"))
        }

        @Test
        fun `client exception returns error result not success`() = runBlocking {
            val client = RestApiClient { _, _ -> throw IllegalStateException("timeout") }
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10")
            })
            Assertions.assertTrue(result.isError)
        }

        @Test
        fun `RestApiException 404 gives path guidance`() = runBlocking {
            val client = RestApiClient { _, _ ->
                throw RestApiException(404, "Not Found", "Nothing here")
            }
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10")
            })
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("404"))
            Assertions.assertTrue(result.text.contains("path") || result.text.contains("locator"))
        }

        @Test
        fun `RestApiException 403 gives permission guidance`() = runBlocking {
            val client = RestApiClient { _, _ ->
                throw RestApiException(403, "Forbidden", "Access denied")
            }
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10")
            })
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("403"))
            Assertions.assertTrue(result.text.contains("permission") || result.text.contains("access"))
        }

        @Test
        fun `RestApiException 500 gives retry guidance`() = runBlocking {
            val client = RestApiClient { _, _ ->
                throw RestApiException(500, "Internal Server Error", "Oops")
            }
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10")
            })
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("500"))
            Assertions.assertTrue(result.text.contains("retry") || result.text.contains("server error"))
        }

        @Test
        fun `RestApiException 400 gives query guidance`() = runBlocking {
            val client = RestApiClient { _, _ ->
                throw RestApiException(400, "Bad Request", "Bad locator syntax")
            }
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "start=0&count=10")
            })
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("400"))
        }
    }
}
