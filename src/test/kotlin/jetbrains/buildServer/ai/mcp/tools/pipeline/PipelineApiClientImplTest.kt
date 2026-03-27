package jetbrains.buildServer.ai.mcp.tools.pipeline

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.unmockkAll
import jetbrains.buildServer.ai.mcp.tools.rest.impl.McpToolExecutionContext
import jetbrains.buildServer.controllers.fakes.FakeHttpRequestsFactory
import jetbrains.buildServer.controllers.fakes.FakeHttpServletRequest
import jetbrains.buildServer.maintenance.TeamCityDispatcherServlet
import jetbrains.buildServer.maintenance.WebDispatcherServlet
import jetbrains.buildServer.users.SUser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PipelineApiClientImplTest {
    private val fakeHttpRequestsFactory = mockk<FakeHttpRequestsFactory>()
    private val executionContext = McpToolExecutionContext()
    private val user = mockk<SUser>(relaxed = true)

    private val client = PipelineApiClientImpl(executionContext, fakeHttpRequestsFactory)

    @AfterEach
    fun tearDown() {
        TeamCityDispatcherServlet.webDispatcherServlet = null
        unmockkAll()
    }

    @Test
    fun `get dispatches through web dispatcher`() {
        val fakeRequest = FakeHttpServletRequest()
        every { fakeHttpRequestsFactory.get(any(), any()) } returns fakeRequest

        val dispatcher = mockk<WebDispatcherServlet>()
        every { dispatcher.service(any(), any()) } answers {
            secondArg<jetbrains.buildServer.controllers.fakes.FakeHttpServletResponse>().apply {
                status = 200
                writer.write("""{"ok":true}""")
            }
        }
        TeamCityDispatcherServlet.webDispatcherServlet = dispatcher

        val result = runBlocking {
            executionContext.withOperationContext(user = user) {
                client.get("/app/pipeline/Test", "parentProjectExtId=Root")
            }
        }

        assertEquals(200, result.statusCode)
        assertEquals("""{"ok":true}""", result.body)
        verify { fakeHttpRequestsFactory.get("/pipeline/Test", "parentProjectExtId=Root") }
        assertEquals("GET", fakeRequest.method)
        assertEquals("application/json", fakeRequest.getHeader("accept"))
        assertTrue(fakeRequest.getAttribute("INTERNAL_REQUEST") != null)
    }

    @Test
    fun `post sends body through web dispatcher`() {
        val fakeRequest = FakeHttpServletRequest()
        every { fakeHttpRequestsFactory.get(any(), any()) } returns fakeRequest

        val dispatcher = mockk<WebDispatcherServlet>()
        every { dispatcher.service(any(), any()) } answers {
            secondArg<jetbrains.buildServer.controllers.fakes.FakeHttpServletResponse>().apply {
                status = 200
                writer.write("""{"saved":true}""")
            }
        }
        TeamCityDispatcherServlet.webDispatcherServlet = dispatcher

        val result = runBlocking {
            executionContext.withOperationContext(user = user) {
                client.post("/app/pipeline/schema/generate", "pipelineId=Pipe1", """{"yaml":"jobs:{}"}""")
            }
        }

        assertEquals(200, result.statusCode)
        assertEquals("""{"saved":true}""", result.body)
        verify { fakeHttpRequestsFactory.get("/pipeline/schema/generate", "pipelineId=Pipe1") }
        assertEquals("POST", fakeRequest.method)
        assertEquals("application/json", fakeRequest.getHeader("content-type"))
    }

    @Test
    fun `returns 503 when dispatcher is unavailable`() = runBlocking {
        val result = executionContext.withOperationContext(user = user) {
            client.get("/app/pipeline/Test", "")
        }

        assertEquals(503, result.statusCode)
    }
}
