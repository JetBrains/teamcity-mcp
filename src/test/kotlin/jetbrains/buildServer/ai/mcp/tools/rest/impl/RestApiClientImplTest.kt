package jetbrains.buildServer.ai.mcp.tools.rest.impl

import io.mockk.every
import io.mockk.mockk
import jetbrains.buildServer.ai.mcp.tools.rest.RestApiException
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.controllers.fakes.FakeHttpRequestsFactory
import jetbrains.buildServer.controllers.fakes.FakeHttpServletRequest
import jetbrains.buildServer.controllers.fakes.FakeHttpServletResponse
import jetbrains.buildServer.users.SUser
import jetbrains.spring.web.UrlMapping
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun `get throws on non-200 status codes`() {
        setupController { response ->
            response.status = 404
            response.writer.write("Not Found")
        }

        val error = assertThrows(RestApiException::class.java) {
            runBlocking {
                executionContext.withOperationContext(user = user) {
                    client.get("/app/rest/missing", "")
                }
            }
        }

        assertEquals(404, error.statusCode)
        assertEquals("Not Found", error.message)
    }

    @Test
    fun `get throws 401 when no user in context`() {
        val error = assertThrows(RestApiException::class.java) {
            runBlocking {
                client.get("/app/rest/server", "")
            }
        }

        assertEquals(401, error.statusCode)
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
    fun `post throws on non-200 status codes`() {
        setupController { response ->
            response.status = 403
            response.writer.write("Forbidden")
        }

        val error = assertThrows(RestApiException::class.java) {
            runBlocking {
                executionContext.withOperationContext(user = user) {
                    client.post("/app/rest/buildQueue", "", """{"buildType":{"id":"bt1"}}""")
                }
            }
        }

        assertEquals(403, error.statusCode)
        assertEquals("Forbidden", error.message)
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
        assertTrue(fakeRequest.getHeader("accept").contains("application/json"))
        assertTrue(fakeRequest.getHeader("accept").contains("text/plain"))
        assertEquals("application/json", fakeRequest.getHeader("content-type"))
    }

    @Test
    fun `put uses text plain content type for scalar bodies`() {
        val fakeRequest = setupController { it.status = 200 }

        runBlocking {
            executionContext.withOperationContext(user = user) {
                client.put("/app/rest/builds/id:1/comment", "", "release candidate")
            }
        }

        assertEquals("PUT", fakeRequest.method)
        assertEquals("text/plain", fakeRequest.getHeader("content-type"))
    }

    @Test
    fun `put uses json content type for object bodies`() {
        val fakeRequest = setupController { it.status = 200 }

        runBlocking {
            executionContext.withOperationContext(user = user) {
                client.put("/app/rest/agents/id:1/enabledInfo", "", """{"status":false}""")
            }
        }

        assertEquals("PUT", fakeRequest.method)
        assertEquals("application/json", fakeRequest.getHeader("content-type"))
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
        assertTrue(fakeRequest.getHeader("accept").contains("application/json"))
        assertTrue(fakeRequest.getHeader("accept").contains("text/plain"))
    }

    @Test
    fun `post throws 401 when no user in context`() {
        val error = assertThrows(RestApiException::class.java) {
            runBlocking {
                client.post("/app/rest/buildQueue", "", """{"buildType":{"id":"bt1"}}""")
            }
        }

        assertEquals(401, error.statusCode)
    }

    @Test
    fun `throws 503 when controller not found`() {
        every { fakeHttpRequestsFactory.get(any(), any()) } returns FakeHttpServletRequest()
        every { urlMapping.handlerMap } returns emptyMap()

        val error = assertThrows(RestApiException::class.java) {
            runBlocking {
                executionContext.withOperationContext(user = user) {
                    client.post("/app/rest/buildQueue", "", """{"buildType":{"id":"bt1"}}""")
                }
            }
        }

        assertEquals(503, error.statusCode)
    }
}
