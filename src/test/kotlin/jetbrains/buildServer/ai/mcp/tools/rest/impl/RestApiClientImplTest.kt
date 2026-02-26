package jetbrains.buildServer.ai.mcp.tools.rest.impl

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jetbrains.buildServer.ServerUrlProvider
import jetbrains.buildServer.util.HTTPRequestBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class RestApiClientImplTest {

    private val requestHandler = mockk<HTTPRequestBuilder.RequestHandler>()
    private val serverUrlProvider = mockk<ServerUrlProvider>().also {
        every { it.rootUrl } returns "http://localhost:8111"
    }

    @Test
    fun `get fails when servlet context is not bound`() {
        val client = RestApiClientImpl(McpToolExecutionContext(), requestHandler, serverUrlProvider)

        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                client.get("/app/rest/server", "fields=count")
            }
        }

        assertEquals(
            "Servlet forwarding context is not available for this tool execution",
            ex.message
        )
    }

    @Test
    fun `get returns response body and status code`() {
        val httpResponse = mockk<HTTPRequestBuilder.Response>()
        every { httpResponse.statusCode } returns 200
        every { httpResponse.bodyAsString } returns """{"ok":true}"""
        every { httpResponse.close() } returns Unit
        every { requestHandler.doSyncRequest(any()) } returns httpResponse

        val context = McpToolExecutionContext()
        val request = mockServletRequest()
        val response = mockk<HttpServletResponse>(relaxed = true)

        val result = runBlocking {
            context.withServletForwardContext(request, response) {
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
        val request = mockServletRequest()
        val response = mockk<HttpServletResponse>(relaxed = true)

        val result = runBlocking {
            context.withServletForwardContext(request, response) {
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
        val request = mockServletRequest()
        val response = mockk<HttpServletResponse>(relaxed = true)

        assertThrows(RuntimeException::class.java) {
            runBlocking {
                context.withServletForwardContext(request, response) {
                    RestApiClientImpl(context, requestHandler, serverUrlProvider).get("/app/rest/server", "")
                }
            }
        }

        verify { httpResponse.close() }
    }

    private fun mockServletRequest(): HttpServletRequest {
        val request = mockk<HttpServletRequest>()
        every { request.getHeader("Authorization") } returns "Bearer test-token"
        return request
    }
}
