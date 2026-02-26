package jetbrains.buildServer.ai.mcp.tools.rest.impl

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import javax.servlet.RequestDispatcher
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class RestApiClientImplTest {

    @Test
    fun `get fails when servlet context is not bound`() {
        val client = RestApiClientImpl(McpToolExecutionContext())

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
    fun `get fails when request dispatcher is not available`() {
        val context = McpToolExecutionContext()
        val request = mockk<HttpServletRequest>()
        val response = mockk<HttpServletResponse>(relaxed = true)
        every { request.getRequestDispatcher("/app/rest/server") } returns null

        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                context.withServletForwardContext(request, response) {
                    RestApiClientImpl(context).get("/app/rest/server", "fields=count")
                }
            }
        }

        assertEquals("RequestDispatcher is not available for path: /app/rest/server", ex.message)
    }

    @Test
    fun `get forwards GET request and captures response`() = runBlocking {
        val context = McpToolExecutionContext()
        val request = mockk<HttpServletRequest>()
        val response = mockk<HttpServletResponse>(relaxed = true)
        val dispatcher = mockk<RequestDispatcher>()

        every { request.getRequestDispatcher("/app/rest/server") } returns dispatcher
        every { dispatcher.forward(any(), any()) } answers {
            val forwardedRequest = firstArg<HttpServletRequest>()
            val forwardedResponse = secondArg<HttpServletResponse>()

            assertEquals("GET", forwardedRequest.method)
            assertEquals("/app/rest/server", forwardedRequest.pathInfo)
            assertEquals("/app/rest/server", forwardedRequest.requestURI)
            assertEquals("fields=count", forwardedRequest.queryString)

            forwardedResponse.status = 201
            forwardedResponse.writer.write("""{"ok":true}""")
        }

        val result = context.withServletForwardContext(request, response) {
            RestApiClientImpl(context).get("/app/rest/server", "fields=count")
        }

        assertEquals(201, result.statusCode)
        assertEquals("""{"ok":true}""", result.body)
    }
}
