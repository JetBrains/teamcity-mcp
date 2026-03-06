package jetbrains.buildServer.ai.mcp.tools.rest.impl

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jetbrains.buildServer.ServerUrlProvider
import jetbrains.buildServer.util.HTTPRequestBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RestApiClientImplTest {

    private val requestHandler = mockk<HTTPRequestBuilder.RequestHandler>()
    private val serverUrlProvider = mockk<ServerUrlProvider>().also {
        every { it.rootUrl } returns "http://localhost:8111"
    }

    @Test
    fun `get returns response body and status code`() {
        val httpResponse = mockk<HTTPRequestBuilder.Response>()
        every { httpResponse.statusCode } returns 200
        every { httpResponse.bodyAsString } returns """{"ok":true}"""
        every { httpResponse.close() } returns Unit
        every { requestHandler.doSyncRequest(any()) } returns httpResponse

        val context = McpToolExecutionContext()

        val result = runBlocking {
            context.withOperationContext(authorizationHeader = "Bearer test-token") {
                RestApiClientImpl(context, requestHandler, serverUrlProvider).get("/app/rest/server", "fields=count")
            }
        }

        assertEquals(200, result.statusCode)
        assertEquals("""{"ok":true}""", result.body)
        verify { httpResponse.close() }
    }

    @Test
    fun `get captures non-200 status codes`() {
        val httpResponse = mockk<HTTPRequestBuilder.Response>()
        every { httpResponse.statusCode } returns 404
        every { httpResponse.bodyAsString } returns "Not Found"
        every { httpResponse.close() } returns Unit
        every { requestHandler.doSyncRequest(any()) } returns httpResponse

        val context = McpToolExecutionContext()

        val result = runBlocking {
            context.withOperationContext(authorizationHeader = "Bearer test-token") {
                RestApiClientImpl(context, requestHandler, serverUrlProvider).get("/app/rest/missing", "")
            }
        }

        assertEquals(404, result.statusCode)
        assertEquals("Not Found", result.body)
    }

    @Test
    fun `get closes response even on read failure`() {
        val httpResponse = mockk<HTTPRequestBuilder.Response>()
        every { httpResponse.statusCode } returns 200
        every { httpResponse.bodyAsString } throws RuntimeException("read error")
        every { httpResponse.close() } returns Unit
        every { requestHandler.doSyncRequest(any()) } returns httpResponse

        val context = McpToolExecutionContext()

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) {
            runBlocking {
                context.withOperationContext(authorizationHeader = "Bearer test-token") {
                    RestApiClientImpl(context, requestHandler, serverUrlProvider).get("/app/rest/server", "")
                }
            }
        }

        verify { httpResponse.close() }
    }

    @Test
    fun `post returns response body and status code`() {
        val httpResponse = mockk<HTTPRequestBuilder.Response>()
        every { httpResponse.statusCode } returns 200
        every { httpResponse.bodyAsString } returns """{"id":123}"""
        every { httpResponse.close() } returns Unit
        every { requestHandler.doSyncRequest(any()) } returns httpResponse

        val context = McpToolExecutionContext()

        val result = runBlocking {
            context.withOperationContext(authorizationHeader = "Bearer test-token") {
                RestApiClientImpl(context, requestHandler, serverUrlProvider)
                    .post("/app/rest/buildQueue", "", """{"buildType":{"id":"bt1"}}""")
            }
        }

        assertEquals(200, result.statusCode)
        assertEquals("""{"id":123}""", result.body)
        verify { httpResponse.close() }
    }

    @Test
    fun `post captures non-200 status codes`() {
        val httpResponse = mockk<HTTPRequestBuilder.Response>()
        every { httpResponse.statusCode } returns 403
        every { httpResponse.bodyAsString } returns "Forbidden"
        every { httpResponse.close() } returns Unit
        every { requestHandler.doSyncRequest(any()) } returns httpResponse

        val context = McpToolExecutionContext()

        val result = runBlocking {
            context.withOperationContext(authorizationHeader = "Bearer test-token") {
                RestApiClientImpl(context, requestHandler, serverUrlProvider)
                    .post("/app/rest/buildQueue", "", """{"buildType":{"id":"bt1"}}""")
            }
        }

        assertEquals(403, result.statusCode)
        assertEquals("Forbidden", result.body)
    }

    @Test
    fun `post closes response even on read failure`() {
        val httpResponse = mockk<HTTPRequestBuilder.Response>()
        every { httpResponse.statusCode } returns 200
        every { httpResponse.bodyAsString } throws RuntimeException("read error")
        every { httpResponse.close() } returns Unit
        every { requestHandler.doSyncRequest(any()) } returns httpResponse

        val context = McpToolExecutionContext()

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) {
            runBlocking {
                context.withOperationContext(authorizationHeader = "Bearer test-token") {
                    RestApiClientImpl(context, requestHandler, serverUrlProvider)
                        .post("/app/rest/buildQueue", "", """{"buildType":{"id":"bt1"}}""")
                }
            }
        }

        verify { httpResponse.close() }
    }

    @Test
    fun `get works without authorization header`() {
        val httpResponse = mockk<HTTPRequestBuilder.Response>()
        every { httpResponse.statusCode } returns 200
        every { httpResponse.bodyAsString } returns """{"ok":true}"""
        every { httpResponse.close() } returns Unit
        every { requestHandler.doSyncRequest(any()) } returns httpResponse

        val context = McpToolExecutionContext()

        val result = runBlocking {
            context.withOperationContext(authorizationHeader = null) {
                RestApiClientImpl(context, requestHandler, serverUrlProvider).get("/app/rest/server", "")
            }
        }

        assertEquals(200, result.statusCode)
    }
}
