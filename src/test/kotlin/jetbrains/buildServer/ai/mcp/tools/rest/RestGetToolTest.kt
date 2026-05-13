package jetbrains.buildServer.ai.mcp.tools.rest

import jetbrains.buildServer.ai.mcp.tools.McpToolResult
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
    ) = object : RestApiClient {
        override suspend fun get(path: String, query: String) = RestApiResponse(body, statusCode, truncated)
        override suspend fun post(path: String, query: String, body: String) = throw UnsupportedOperationException()
        override suspend fun put(path: String, query: String, body: String) = throw UnsupportedOperationException()
        override suspend fun delete(path: String, query: String) = throw UnsupportedOperationException()
    }

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

        override suspend fun post(path: String, query: String, body: String): RestApiResponse =
            throw UnsupportedOperationException()

        override suspend fun put(path: String, query: String, body: String): RestApiResponse =
            throw UnsupportedOperationException()

        override suspend fun delete(path: String, query: String): RestApiResponse =
            throw UnsupportedOperationException()
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
    fun `description references the rest api guide resource`() {
        val description = tool().description
        Assertions.assertTrue(description.contains("teamcity://guides/rest-api"))
    }

    @Test
    fun `query schema description clarifies plain text endpoints do not use fields`() {
        val queryDescription = tool().inputSchema.properties!!
            .getValue("query")
            .jsonObject
            .getValue("description")
            .jsonPrimitive
            .content
        Assertions.assertTrue(queryDescription.contains("plain text"))
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
                put("query", "?locator=start:0,count:10&fields=count")
            })
            Assertions.assertEquals("locator=start:0,count:10&fields=count", client.capturedQuery)
        }
    }

    // -----------------------------------------------------------------------
    // Pagination enforcement
    // -----------------------------------------------------------------------

    @Nested
    inner class PaginationEnforcement {

        // --- Locator-only pagination: count and start must always be in the locator ---

        @Test
        fun `empty query gets locator with default pagination`() {
            val result = tool().ensurePaging("")
            Assertions.assertTrue(result.enforced)
            Assertions.assertTrue(result.query.contains("locator="))
            Assertions.assertTrue(result.query.contains("start:0"))
            Assertions.assertTrue(result.query.contains("count:${RestGetTool.DEFAULT_PAGE_SIZE}"))
            // Must NOT have top-level start= or count=
            val params = result.query.split("&").map { it.substringBefore("=") }
            Assertions.assertFalse(params.contains("start"))
            Assertions.assertFalse(params.contains("count"))
            Assertions.assertNotNull(result.note)
        }

        @Test
        fun `query with fields only gets locator with default pagination`() {
            val result = tool().ensurePaging("fields=build(id)")
            Assertions.assertTrue(result.enforced)
            Assertions.assertTrue(result.query.contains("locator=start:0,count:${RestGetTool.DEFAULT_PAGE_SIZE}"))
            Assertions.assertTrue(result.query.contains("fields=build(id)"))
        }

        @Test
        fun `existing locator without pagination gets count and start appended`() {
            val result = tool().ensurePaging("locator=buildType:(id:BT1)&fields=build(id)")
            Assertions.assertTrue(result.enforced)
            Assertions.assertTrue(result.query.contains("locator=buildType:(id:BT1),start:0,count:${RestGetTool.DEFAULT_PAGE_SIZE}"))
            Assertions.assertTrue(result.query.contains("fields=build(id)"))
        }

        @Test
        fun `pagination in locator is detected and not modified`() {
            val original = "locator=buildType:(id:BT1),start:0,count:10&fields=build(id)"
            val result = tool().ensurePaging(original)
            Assertions.assertFalse(result.enforced)
            Assertions.assertEquals(original, result.query)
        }

        @Test
        fun `locator with count but no start gets start added`() {
            val result = tool().ensurePaging("locator=buildType:(id:BT1),count:10&fields=build(id)")
            Assertions.assertTrue(result.enforced)
            Assertions.assertTrue(result.query.contains("start:0"))
            Assertions.assertTrue(result.query.contains("count:10"))
        }

        @Test
        fun `locator with start but no count gets count added`() {
            val result = tool().ensurePaging("locator=buildType:(id:BT1),start:5&fields=build(id)")
            Assertions.assertTrue(result.enforced)
            Assertions.assertTrue(result.query.contains("start:5"))
            Assertions.assertTrue(result.query.contains("count:${RestGetTool.DEFAULT_PAGE_SIZE}"))
        }

        @Test
        fun `whitespace-only query gets locator with default pagination`() {
            val result = tool().ensurePaging("   ")
            Assertions.assertTrue(result.enforced)
            Assertions.assertTrue(result.query.contains("locator="))
            Assertions.assertTrue(result.query.contains("start:0"))
            Assertions.assertTrue(result.query.contains("count:${RestGetTool.DEFAULT_PAGE_SIZE}"))
        }

        @Test
        fun `pagination detected in locator with nested parentheses`() {
            val original = "locator=buildType:(project:(id:P1)),count:10,start:0&fields=build(id)"
            val result = tool().ensurePaging(original)
            Assertions.assertFalse(result.enforced)
            Assertions.assertEquals(original, result.query)
        }

        // --- Top-level start/count migration into locator ---

        @Test
        fun `top-level start and count are migrated into new locator`() {
            val result = tool().ensurePaging("start=5&count=20&fields=build(id)")
            Assertions.assertTrue(result.query.contains("locator=start:5,count:20"))
            Assertions.assertTrue(result.query.contains("fields=build(id)"))
            // Must NOT have top-level start= or count=
            val topLevelParams = result.query.split("&")
                .filter { !it.startsWith("locator=") }
                .map { it.substringBefore("=") }
            Assertions.assertFalse(topLevelParams.contains("start"))
            Assertions.assertFalse(topLevelParams.contains("count"))
            Assertions.assertNotNull(result.note)
        }

        @Test
        fun `top-level start and count are migrated into existing locator`() {
            val result = tool().ensurePaging("locator=buildType:(id:BT1)&start=5&count=20&fields=build(id)")
            Assertions.assertTrue(result.query.contains("locator=buildType:(id:BT1),start:5,count:20"))
            Assertions.assertTrue(result.query.contains("fields=build(id)"))
            val topLevelParams = result.query.split("&")
                .filter { !it.startsWith("locator=") }
                .map { it.substringBefore("=") }
            Assertions.assertFalse(topLevelParams.contains("start"))
            Assertions.assertFalse(topLevelParams.contains("count"))
        }

        @Test
        fun `top-level count only is migrated and start added`() {
            val result = tool().ensurePaging("count=10&fields=build(id)")
            Assertions.assertTrue(result.query.contains("start:0"))
            Assertions.assertTrue(result.query.contains("count:10"))
            val topLevelParams = result.query.split("&")
                .filter { !it.startsWith("locator=") }
                .map { it.substringBefore("=") }
            Assertions.assertFalse(topLevelParams.contains("count"))
        }

        @Test
        fun `top-level start only is migrated and count added`() {
            val result = tool().ensurePaging("start=5&fields=build(id)")
            Assertions.assertTrue(result.query.contains("start:5"))
            Assertions.assertTrue(result.query.contains("count:${RestGetTool.DEFAULT_PAGE_SIZE}"))
            val topLevelParams = result.query.split("&")
                .filter { !it.startsWith("locator=") }
                .map { it.substringBefore("=") }
            Assertions.assertFalse(topLevelParams.contains("start"))
        }

        @Test
        fun `top-level count with locator start stays in locator`() {
            val result = tool().ensurePaging("locator=buildType:(id:BT1),start:0&count=10")
            Assertions.assertTrue(result.query.contains("start:0"))
            Assertions.assertTrue(result.query.contains("count:10"))
            val topLevelParams = result.query.split("&")
                .filter { !it.startsWith("locator=") }
                .map { it.substringBefore("=") }
            Assertions.assertFalse(topLevelParams.contains("count"))
        }

        @Test
        fun `top-level start with locator count stays in locator`() {
            val result = tool().ensurePaging("start=0&locator=buildType:(id:BT1),count:10")
            Assertions.assertTrue(result.query.contains("start:0"))
            Assertions.assertTrue(result.query.contains("count:10"))
            val topLevelParams = result.query.split("&")
                .filter { !it.startsWith("locator=") }
                .map { it.substringBefore("=") }
            Assertions.assertFalse(topLevelParams.contains("start"))
        }

        @Test
        fun `locator pagination takes precedence over duplicate top-level params`() {
            // Locator already has start and count — top-level duplicates are stripped, not merged
            val result = tool().ensurePaging("locator=buildType:(id:BT1),start:0,count:10&start=5&count=20&fields=build(id)")
            Assertions.assertTrue(result.query.contains("start:0"))
            Assertions.assertTrue(result.query.contains("count:10"))
            Assertions.assertFalse(result.query.contains("start:5"))
            Assertions.assertFalse(result.query.contains("count:20"))
            Assertions.assertFalse(result.query.contains("start="))
            Assertions.assertFalse(result.query.contains("count="))
        }

        // --- Count capping ---

        @Test
        fun `count exceeding max is capped in locator`() {
            val result = tool().ensurePaging("locator=buildType:(id:BT1),start:0,count:200&fields=build(id)")
            Assertions.assertTrue(result.query.contains("count:${RestGetTool.MAX_PAGE_SIZE}"))
            Assertions.assertFalse(result.query.contains("count:200"))
            Assertions.assertNotNull(result.note)
        }

        @Test
        fun `top-level count exceeding max is migrated and capped`() {
            val result = tool().ensurePaging("start=0&count=500&fields=build(id)")
            Assertions.assertTrue(result.query.contains("count:${RestGetTool.MAX_PAGE_SIZE}"))
            Assertions.assertFalse(result.query.contains("count=500"))
            Assertions.assertFalse(result.query.contains("count:500"))
            Assertions.assertNotNull(result.note)
        }

        @Test
        fun `count exceeding max capped end-to-end via client`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=buildType:(id:BT1),start:0,count:500&fields=build(id)")
            })
            Assertions.assertTrue(client.capturedQuery!!.contains("count:${RestGetTool.MAX_PAGE_SIZE}"))
            Assertions.assertFalse(client.capturedQuery!!.contains("count:500"))
        }

        @Test
        fun `count at max in locator is not modified`() {
            val original = "locator=buildType:(id:BT1),start:0,count:${RestGetTool.MAX_PAGE_SIZE}&fields=build(id)"
            val result = tool().ensurePaging(original)
            Assertions.assertEquals(original, result.query)
        }

        // --- Edge cases ---

        @Test
        fun `pagination not enforced for single-resource paths`() {
            // When requesting a single resource (e.g. /app/rest/builds/id:123),
            // the query typically has fields but no locator/paging. We still add
            // pagination because the tool can't distinguish single vs collection.
            // This is harmless — single-resource endpoints ignore start/count.
            val result = tool().ensurePaging("fields=id,status")
            Assertions.assertTrue(result.enforced)
        }

        @Test
        fun `migration note mentions deprecated top-level params`() {
            val result = tool().ensurePaging("start=0&count=10&fields=build(id)")
            Assertions.assertNotNull(result.note)
            Assertions.assertTrue(result.note!!.contains("locator"))
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
        fun `passes pagination-enforced query to client with locator`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "fields=build(id)")
            })
            Assertions.assertTrue(client.capturedQuery!!.contains("fields=build(id)"))
            Assertions.assertTrue(client.capturedQuery!!.contains("locator="))
            Assertions.assertTrue(client.capturedQuery!!.contains("start:0"))
            Assertions.assertTrue(client.capturedQuery!!.contains("count:"))
        }

        @Test
        fun `passes original query when pagination present in locator`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=start:0,count:5&fields=build(id)")
            })
            Assertions.assertEquals("locator=start:0,count:5&fields=build(id)", client.capturedQuery)
        }

        @Test
        fun `trims whitespace from path and query`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "  /app/rest/builds  ")
                put("query", "  locator=start:0,count:5  ")
            })
            Assertions.assertEquals("/app/rest/builds", client.capturedPath)
            Assertions.assertEquals("locator=start:0,count:5", client.capturedQuery)
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
                put("query", "locator=start:0,count:10&fields=count")
            })
            Assertions.assertFalse(result.isError)
            val payload = parseResultJson(result.text)
            val meta = payload["meta"]!!.jsonObject
            Assertions.assertEquals("/app/rest/builds?locator=start:0,count:10&fields=count", meta["url"]!!.toString().trim('"'))
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
                put("query", "locator=branch:default:any,start:0,count:10&fields=build(id,branchName)")
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
                put("query", "locator=start:0,count:10&fields=build(id)")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            Assertions.assertTrue(notes.contains("truncated"))
        }

        @Test
        fun `response includes nextHref flag and note when present in json body`() = runBlocking {
            val body = """{"count":10,"nextHref":"/app/rest/builds?locator=start:10,count:10","build":[]}"""
            val result = tool(succeedingClient(body)).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=start:0,count:10&fields=build(id)")
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
                put("query", "locator=start:0,count:10&fields=count")
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
                put("query", "locator=start:0,count:10&fields=build(id)")
            })
            val meta = parseResultJson(result.text)["meta"]!!.jsonObject
            Assertions.assertEquals(404, meta["statusCode"]!!.toString().toInt())
        }

        @Test
        fun `response includes missing-fields warning note when fields absent`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=start:0,count:10")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            Assertions.assertTrue(notes.contains("No 'fields'"))
        }

        @Test
        fun `no missing-fields warning when fields is present`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=start:0,count:10&fields=build(id)")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            Assertions.assertFalse(notes.contains("No 'fields'"))
        }

        @Test
        fun `response adds note when collection is empty`() = runBlocking {
            val body = """{"count":0,"build":[]}"""
            val result = tool(succeedingClient(body)).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=start:0,count:10&fields=build(id)")
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
                put("query", "locator=start:0,count:10&fields=build(id)")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            Assertions.assertFalse(notes.contains("0 items"))
        }

        @Test
        fun `no empty hint when count is not zero`() = runBlocking {
            val body = """{"count":5,"build":[{"id":1}]}"""
            val result = tool(succeedingClient(body)).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=start:0,count:10&fields=build(id)")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            Assertions.assertFalse(notes.contains("0 items"))
        }

        @Test
        fun `no nextHref hint when body does not contain nextHref`() = runBlocking {
            val body = """{"count":3,"build":[{"id":1},{"id":2},{"id":3}]}"""
            val result = tool(succeedingClient(body)).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=start:0,count:10&fields=build(id)")
            })
            val meta = parseResultJson(result.text)["meta"]!!.jsonObject
            Assertions.assertEquals("false", meta["hasNextHref"]!!.toString())
        }

        @Test
        fun `aggregated status endpoint returns text plain content with bodyText`() = runBlocking {
            val body = "SUCCESS"
            val result = tool(succeedingClient(body)).execute(buildJsonObject {
                put("path", "/app/rest/builds/aggregated/buildType:(id:BT1)/status")
                put("query", "locator=start:0,count:10")
            })
            val payload = parseResultJson(result.text)
            Assertions.assertEquals("text/plain", payload["contentType"]!!.toString().trim('"'))
            Assertions.assertEquals(body, payload["bodyText"]!!.jsonPrimitive.content)
            Assertions.assertFalse(payload.containsKey("body"))
        }

        @Test
        fun `plain text endpoint does not add missing fields note`() = runBlocking {
            val result = tool(succeedingClient("FAILURE")).execute(buildJsonObject {
                put("path", "/app/rest/builds/aggregated/buildType:(id:BT1)/status")
                put("query", "locator=start:0,count:10")
            })
            val notes = parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()
            Assertions.assertFalse(notes.contains("No 'fields'"))
        }

        @Test
        fun `non plain text endpoint with non json body falls back to bodyText with note`() = runBlocking {
            val result = tool(succeedingClient("{invalid-json")).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=start:0,count:10&fields=build(id)")
            })
            val payload = parseResultJson(result.text)
            val meta = payload["meta"]!!.jsonObject
            Assertions.assertEquals("text/plain", payload["contentType"]!!.toString().trim('"'))
            Assertions.assertEquals("{invalid-json", payload["bodyText"]!!.jsonPrimitive.content)
            val notes = meta["notes"]!!.jsonArray.toString()
            Assertions.assertTrue(notes.contains("not valid JSON"))
        }

        @Test
        fun `plain text endpoint keeps hasNextHref false even when text mentions nextHref`() = runBlocking {
            val result = tool(succeedingClient("line mentions nextHref here")).execute(buildJsonObject {
                put("path", "/app/rest/builds/aggregated/buildType:(id:BT1)/status")
                put("query", "locator=start:0,count:10")
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

        private fun throwingClient(e: Exception) = object : RestApiClient {
            override suspend fun get(path: String, query: String): RestApiResponse = throw e
            override suspend fun post(path: String, query: String, body: String): RestApiResponse = throw e
            override suspend fun put(path: String, query: String, body: String): RestApiResponse = throw e
            override suspend fun delete(path: String, query: String): RestApiResponse = throw e
        }

        @Test
        fun `client exception returns error result with message`() = runBlocking {
            val client = throwingClient(RuntimeException("Connection refused"))
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
            })
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("Connection refused"))
        }

        @Test
        fun `client exception returns error result not success`() = runBlocking {
            val client = throwingClient(IllegalStateException("timeout"))
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=start:0,count:10")
            })
            Assertions.assertTrue(result.isError)
        }

        @Test
        fun `RestApiException 404 gives path guidance`() = runBlocking {
            val client = throwingClient(RestApiException(404, "Not Found", "Nothing here"))
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=start:0,count:10")
            })
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("404"))
            Assertions.assertTrue(result.text.contains("path") || result.text.contains("locator"))
        }

        @Test
        fun `RestApiException 403 gives permission guidance`() = runBlocking {
            val client = throwingClient(RestApiException(403, "Forbidden", "Access denied"))
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=start:0,count:10")
            })
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("403"))
            Assertions.assertTrue(result.text.contains("permission") || result.text.contains("access"))
        }

        @Test
        fun `RestApiException 500 gives retry guidance`() = runBlocking {
            val client = throwingClient(RestApiException(500, "Internal Server Error", "Oops"))
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=start:0,count:10")
            })
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("500"))
            Assertions.assertTrue(result.text.contains("retry") || result.text.contains("server error"))
        }

        @Test
        fun `RestApiException 400 gives query guidance`() = runBlocking {
            val client = throwingClient(RestApiException(400, "Bad Request", "Bad locator syntax"))
            val result = tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=start:0,count:10")
            })
            Assertions.assertTrue(result.isError)
            Assertions.assertTrue(result.text.contains("400"))
        }
    }

    // -----------------------------------------------------------------------
    // Branch awareness — TW-99613 (notes only; query is never mutated)
    // -----------------------------------------------------------------------

    @Nested
    inner class BranchAwareness {

        private fun notesOf(result: McpToolResult): String =
            parseResultJson(result.text)["meta"]!!.jsonObject["notes"]!!.jsonArray.toString()

        // --- Top-level locator on /app/rest/builds ---

        @Test
        fun `warns on builds path when locator missing branch`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=buildType:(id:BT1),start:0,count:10&fields=build(id,branchName)")
            })
            Assertions.assertTrue(notesOf(result).contains("default-branch"))
        }

        @Test
        fun `does not warn on builds path when branch dimension present`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=buildType:(id:BT1),branch:name:main,start:0,count:10&fields=build(id,branchName)")
            })
            Assertions.assertFalse(notesOf(result).contains("default-branch"))
        }

        @Test
        fun `does not warn on builds path when branch default any present`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=branch:default:any,start:0,count:10&fields=build(id,branchName)")
            })
            Assertions.assertFalse(notesOf(result).contains("default-branch"))
        }

        @Test
        fun `does not warn on builds path single resource lookup`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/builds/id:123")
                put("query", "fields=id,status")
            })
            Assertions.assertFalse(notesOf(result).contains("default-branch"))
        }

        @Test
        fun `does not warn on builds path when defaultFilter false`() = runBlocking {
            // defaultFilter:false suppresses BuildPromotionFinder's default-branch fallback;
            // warning here would be a false alarm.
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=buildType:(id:BT1),defaultFilter:false,start:0,count:10&fields=build(id,branchName)")
            })
            Assertions.assertFalse(notesOf(result).contains("default-branch"))
        }

        // --- Issue 2: path-embedded locator on /app/rest/builds/aggregated/<locator>/status ---

        @Test
        fun `warns on aggregated status when path locator missing branch`() = runBlocking {
            // /app/rest/builds/aggregated/<locator>/status routes through BuildPromotionFinder
            // — same default-branch trap, but the locator is in the path, not in `query`.
            val result = tool(succeedingClient("UNKNOWN")).execute(buildJsonObject {
                put("path", "/app/rest/builds/aggregated/buildType:(id:BT1)/status")
            })
            Assertions.assertTrue(notesOf(result).contains("default-branch"))
        }

        @Test
        fun `does not warn on aggregated status when path locator has branch`() = runBlocking {
            val result = tool(succeedingClient("SUCCESS")).execute(buildJsonObject {
                put("path", "/app/rest/builds/aggregated/buildType:(id:BT1),branch:default:any/status")
            })
            Assertions.assertFalse(notesOf(result).contains("default-branch"))
        }

        @Test
        fun `does not warn on aggregated status when path locator has defaultFilter false`() = runBlocking {
            val result = tool(succeedingClient("SUCCESS")).execute(buildJsonObject {
                put("path", "/app/rest/builds/aggregated/buildType:(id:BT1),defaultFilter:false/status")
            })
            Assertions.assertFalse(notesOf(result).contains("default-branch"))
        }

        @Test
        fun `no fields tip on aggregated status because response is plain text`() = runBlocking {
            // The fields tip is about `fields=build(...)` selection on a builds list — not
            // applicable on aggregated/status (plain-text endpoint).
            val result = tool(succeedingClient("FAILURE")).execute(buildJsonObject {
                put("path", "/app/rest/builds/aggregated/buildType:(id:BT1)/status")
            })
            Assertions.assertFalse(notesOf(result).contains("Include `branchName`"))
        }

        @Test
        fun `does not warn on non-builds path`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/projects")
                put("query", "locator=start:0,count:10&fields=project(id)")
            })
            Assertions.assertFalse(notesOf(result).contains("default-branch"))
        }

        @Test
        fun `does not warn on buildQueue because QueuedBuildFinder has no branch dimension`() = runBlocking {
            // /app/rest/buildQueue's QueuedBuildFinder does not declare `branch:` — warning
            // (or injecting) would mislead the agent into producing an unknown-dimension error.
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/buildQueue")
                put("query", "locator=start:0,count:5")
            })
            Assertions.assertFalse(notesOf(result).contains("default-branch"))
        }

        // --- Nested build:(...) sub-locator on changes/testOccurrences/problemOccurrences ---

        @Test
        fun `warns on testOccurrences when build sublocator lacks branch`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/testOccurrences")
                put("query", "locator=build:(buildType:(id:BT1)),start:0,count:10&fields=testOccurrence(name)")
            })
            val notes = notesOf(result)
            Assertions.assertTrue(notes.contains("build:(...)") && notes.contains("default-branch"),
                "expected nested-locator warning, got: $notes")
        }

        @Test
        fun `warns on problemOccurrences when build sublocator lacks branch`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/problemOccurrences")
                put("query", "locator=build:(buildType:(id:BT1)),start:0,count:10")
            })
            Assertions.assertTrue(notesOf(result).contains("default-branch"))
        }

        @Test
        fun `warns on changes when build sublocator lacks branch`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/changes")
                put("query", "locator=build:(buildType:(id:BT1)),start:0,count:10")
            })
            Assertions.assertTrue(notesOf(result).contains("default-branch"))
        }

        @Test
        fun `does not warn on testOccurrences when build sublocator already has branch`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/testOccurrences")
                put("query", "locator=build:(buildType:(id:BT1),branch:default:any),start:0,count:10")
            })
            Assertions.assertFalse(notesOf(result).contains("default-branch"))
        }

        @Test
        fun `still warns on testOccurrences when branch is top-level but missing inside build sublocator`() = runBlocking {
            // Top-level `branch:feature-x` filters test-occurrences themselves, but the inner
            // build:(buildType:(...)) is resolved by BuildPromotionFinder independently and still
            // defaults to the default branch. The warning must fire even though `branch:` appears
            // somewhere in the locator.
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/testOccurrences")
                put("query", "locator=build:(buildType:(id:BT1)),branch:feature-x,start:0,count:10")
            })
            val notes = notesOf(result)
            Assertions.assertTrue(notes.contains("build:(...)") && notes.contains("default-branch"),
                "expected nested-locator warning even with top-level branch:, got: $notes")
        }

        @Test
        fun `does not warn on testOccurrences when build sublocator has defaultFilter false`() = runBlocking {
            // `defaultFilter:false` inside the build:() group disables the default-branch fallback
            // for that sub-locator (BuildPromotionFinder.setDefaultsToLocator returns early).
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/testOccurrences")
                put("query", "locator=build:(buildType:(id:BT1),defaultFilter:false),start:0,count:10")
            })
            Assertions.assertFalse(notesOf(result).contains("default-branch"))
        }

        @Test
        fun `warns when only one of multiple build sublocators is missing branch`() = runBlocking {
            // First group has branch, second doesn't → still warn.
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/changes")
                put("query", "locator=or(build:(buildType:(id:A),branch:default:any),build:(buildType:(id:B))),start:0,count:10")
            })
            Assertions.assertTrue(notesOf(result).contains("default-branch"))
        }

        @Test
        fun `does not warn on testOccurrences without build sublocator`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/testOccurrences")
                put("query", "locator=status:FAILURE,start:0,count:10")
            })
            Assertions.assertFalse(notesOf(result).contains("default-branch"))
        }

        // --- branchName fields tip (only on /app/rest/builds) ---

        @Test
        fun `flags missing branchName on builds path when build subselect present`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=start:0,count:10&fields=build(id,number,status)")
            })
            Assertions.assertTrue(notesOf(result).contains("Include `branchName`"))
        }

        @Test
        fun `does not flag branchName when already in build subselect`() = runBlocking {
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=branch:default:any,start:0,count:10&fields=build(id,branchName,number)")
            })
            Assertions.assertFalse(notesOf(result).contains("Include `branchName`"))
        }

        @Test
        fun `does not flag branchName on testOccurrences even with build subselect in fields`() = runBlocking {
            // The fields tip is per-build branchName visibility; only makes sense when the
            // response IS a builds list, not test occurrences.
            val result = tool(succeedingClient("{}")).execute(buildJsonObject {
                put("path", "/app/rest/testOccurrences")
                put("query", "locator=build:(buildType:(id:BT1),branch:default:any),start:0,count:10&fields=testOccurrence(name,build(id))")
            })
            Assertions.assertFalse(notesOf(result).contains("Include `branchName`"))
        }

        // --- Query is never mutated ---

        @Test
        fun `query is never mutated for branch`() = runBlocking {
            val client = CapturingClient()
            tool(client).execute(buildJsonObject {
                put("path", "/app/rest/builds")
                put("query", "locator=buildType:(id:BT1),start:0,count:10&fields=build(id,branchName)")
            })
            Assertions.assertFalse(client.capturedQuery!!.contains("branch:"),
                "branch should NOT have been injected; got: ${client.capturedQuery}")
        }

        // --- Description references ---

        @Test
        fun `description mentions branch awareness`() {
            val description = tool().description
            Assertions.assertTrue(description.contains("Branch awareness"),
                "description should foreground branch awareness")
        }
    }
}
