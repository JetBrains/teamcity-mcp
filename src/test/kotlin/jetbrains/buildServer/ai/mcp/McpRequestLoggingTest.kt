package jetbrains.buildServer.ai.mcp

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import javax.servlet.http.HttpServletRequest

class McpRequestLoggingTest {

    @Test
    fun `redacts Authorization header`() {
        val request = mockRequestWithHeaders(
            "Authorization" to "Bearer secret-token",
            "Accept" to "application/json"
        )

        val sanitized = sanitizeHeadersForLogging(request)

        assertEquals("*****", sanitized["Authorization"])
        assertEquals("application/json", sanitized["Accept"])
    }

    @Test
    fun `redacts Cookie header`() {
        val request = mockRequestWithHeaders(
            "Cookie" to "session=abc123"
        )

        val sanitized = sanitizeHeadersForLogging(request)

        assertEquals("*****", sanitized["Cookie"])
    }

    @Test
    fun `redacts Set-Cookie header`() {
        val request = mockRequestWithHeaders(
            "Set-Cookie" to "id=abc; Path=/"
        )

        val sanitized = sanitizeHeadersForLogging(request)

        assertEquals("*****", sanitized["Set-Cookie"])
    }

    @Test
    fun `redacts X-TC-CSRF-Token header`() {
        val request = mockRequestWithHeaders(
            "X-TC-CSRF-Token" to "csrf-value"
        )

        val sanitized = sanitizeHeadersForLogging(request)

        assertEquals("*****", sanitized["X-TC-CSRF-Token"])
    }

    @Test
    fun `is case-insensitive for sensitive header names`() {
        val request = mockRequestWithHeaders(
            "authorization" to "Bearer token",
            "COOKIE" to "session=x",
            "x-tc-csrf-token" to "csrf"
        )

        val sanitized = sanitizeHeadersForLogging(request)

        assertEquals("*****", sanitized["authorization"])
        assertEquals("*****", sanitized["COOKIE"])
        assertEquals("*****", sanitized["x-tc-csrf-token"])
    }

    @Test
    fun `preserves non-sensitive headers`() {
        val request = mockRequestWithHeaders(
            "Content-Type" to "application/json",
            "User-Agent" to "TestClient/1.0",
            "Mcp-Session-Id" to "session-123"
        )

        val sanitized = sanitizeHeadersForLogging(request)

        assertEquals("application/json", sanitized["Content-Type"])
        assertEquals("TestClient/1.0", sanitized["User-Agent"])
        assertEquals("session-123", sanitized["Mcp-Session-Id"])
    }

    @Test
    fun `handles request with no headers`() {
        val request = mockRequestWithHeaders()

        val sanitized = sanitizeHeadersForLogging(request)

        assertTrue(sanitized.isEmpty())
    }

    private fun mockRequestWithHeaders(vararg headers: Pair<String, String>): HttpServletRequest {
        val request = mockk<HttpServletRequest>(relaxed = true)
        val headerNames = headers.map { it.first }
        every { request.headerNames } returns Collections.enumeration(headerNames)
        headers.forEach { (name, value) ->
            every { request.getHeader(name) } returns value
        }
        return request
    }
}
