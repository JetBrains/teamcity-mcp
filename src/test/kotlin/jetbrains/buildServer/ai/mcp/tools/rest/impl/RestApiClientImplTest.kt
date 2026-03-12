package jetbrains.buildServer.ai.mcp.tools.rest.impl

import io.mockk.every
import io.mockk.mockk
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.controllers.fakes.FakeHttpRequestsFactory
import jetbrains.buildServer.controllers.fakes.FakeHttpServletRequest
import jetbrains.buildServer.controllers.fakes.FakeHttpServletResponse
import jetbrains.buildServer.users.SUser
import jetbrains.spring.web.UrlMapping
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder

class RestApiClientImplTest {

    private val fakeHttpRequestsFactory = mockk<FakeHttpRequestsFactory>()
    private val urlMapping = mockk<UrlMapping>()
    private val executionContext = McpToolExecutionContext()
    private val user = mockk<SUser>(relaxed = true)

    private val client = RestApiClientImpl(
        executionContext, fakeHttpRequestsFactory, urlMapping
    )

    private fun setupController(block: (FakeHttpServletResponse) -> Unit): FakeHttpServletRequest {
        val fakeRequest = FakeHttpServletRequest()
        every { fakeHttpRequestsFactory.get(any(), any()) } returns fakeRequest

        val controller = mockk<BaseController>()
        every { urlMapping.handlerMap } returns mapOf("/app/rest/**" to controller)
        every { controller.handleRequestInternal(any(), any()) } answers {
            block(secondArg())
            null
        }

        return fakeRequest
    }

    @Test
    fun `get returns response body and status code`() {
        setupController { response ->
            response.status = 200
            response.writer.write("""{"ok":true}""")
        }

        val result = runBlocking {
            executionContext.withOperationContext(user = user) {
                client.get("/app/rest/server", "fields=count")
            }
        }

        assertEquals(200, result.statusCode)
        assertEquals("""{"ok":true}""", result.body)
    }

    @Test
    fun `get creates request with GET method`() {
        val fakeRequest = setupController { it.status = 200 }

        runBlocking {
            executionContext.withOperationContext(user = user) {
                client.get("/app/rest/server", "")
            }
        }

        assertEquals("GET", fakeRequest.method)
    }

    @Test
    fun `get captures non-200 status codes`() {
        setupController { response ->
            response.status = 404
            response.writer.write("Not Found")
        }

        val result = runBlocking {
            executionContext.withOperationContext(user = user) {
                client.get("/app/rest/missing", "")
            }
        }

        assertEquals(404, result.statusCode)
        assertEquals("Not Found", result.body)
    }

    @Test
    fun `get returns 401 when no user in context`() {
        val result = runBlocking {
            client.get("/app/rest/server", "")
        }

        assertEquals(401, result.statusCode)
    }

    @Test
    fun `post dispatches to controller and returns response`() {
        val fakeRequest = setupController { response ->
            response.status = 200
            response.writer.write("""{"id":123}""")
        }

        val result = runBlocking {
            executionContext.withOperationContext(user = user) {
                client.post("/app/rest/buildQueue", "", """{"buildType":{"id":"bt1"}}""")
            }
        }

        assertEquals(200, result.statusCode)
        assertEquals("""{"id":123}""", result.body)
        assertEquals("POST", fakeRequest.method)
    }

    @Test
    fun `post captures non-200 status codes`() {
        setupController { response ->
            response.status = 403
            response.writer.write("Forbidden")
        }

        val result = runBlocking {
            executionContext.withOperationContext(user = user) {
                client.post("/app/rest/buildQueue", "", """{"buildType":{"id":"bt1"}}""")
            }
        }

        assertEquals(403, result.statusCode)
        assertEquals("Forbidden", result.body)
    }

    @Test
    fun `post sets internal request attributes correctly`() {
        val fakeRequest = setupController { it.status = 200 }

        runBlocking {
            executionContext.withOperationContext(user = user) {
                client.post("/app/rest/buildQueue", "", "{}")
            }
        }

        assertEquals("POST", fakeRequest.method)
        assertTrue(fakeRequest.getAttribute("INTERNAL_REQUEST") != null)
        assertEquals("application/json", fakeRequest.getHeader("content-type"))
        assertEquals("application/json", fakeRequest.getHeader("accept"))
    }

    @Test
    fun `controller sees propagated security context`() {
        val authentication = mockk<Authentication>(relaxed = true)
        val securityContext = mockk<SecurityContext>()
        every { securityContext.authentication } returns authentication

        setupController { response ->
            assertEquals(authentication, SecurityContextHolder.getContext().authentication)
            response.status = 200
        }

        runBlocking {
            executionContext.withOperationContext(user = user, capturedSecurityContext = securityContext) {
                client.get("/app/rest/server", "")
            }
        }
    }

    @Test
    fun `get sets internal request attributes correctly`() {
        val fakeRequest = setupController { it.status = 200 }

        runBlocking {
            executionContext.withOperationContext(user = user) {
                client.get("/app/rest/server", "")
            }
        }

        assertEquals("GET", fakeRequest.method)
        assertTrue(fakeRequest.getAttribute("INTERNAL_REQUEST") != null)
        assertEquals("application/json", fakeRequest.getHeader("accept"))
    }

    @Test
    fun `post returns 401 when no user in context`() {
        val result = runBlocking {
            client.post("/app/rest/buildQueue", "", """{"buildType":{"id":"bt1"}}""")
        }

        assertEquals(401, result.statusCode)
    }

    @Test
    fun `returns 503 when controller not found`() {
        every { urlMapping.handlerMap } returns emptyMap()

        val result = runBlocking {
            executionContext.withOperationContext(user = user) {
                client.post("/app/rest/buildQueue", "", """{"buildType":{"id":"bt1"}}""")
            }
        }

        assertEquals(503, result.statusCode)
    }
}
