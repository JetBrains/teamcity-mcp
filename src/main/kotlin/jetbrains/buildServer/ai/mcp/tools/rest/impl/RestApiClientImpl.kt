package jetbrains.buildServer.ai.mcp.tools.rest.impl

import jetbrains.buildServer.ai.mcp.tools.rest.RestApiClient
import jetbrains.buildServer.ai.mcp.tools.rest.RestApiException
import jetbrains.buildServer.ai.mcp.tools.rest.RestApiResponse
import jetbrains.buildServer.ai.mcp.tools.rest.RestToolUtils
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.controllers.fakes.FakeHttpRequestsFactory
import jetbrains.buildServer.controllers.fakes.FakeHttpServletResponse
import jetbrains.buildServer.util.http.HttpMethod
import jetbrains.buildServer.web.util.SessionUser
import jetbrains.spring.web.UrlMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream

/**
 * REST API client that dispatches requests internally via the REST controller
 */
@Component
class RestApiClientImpl(
    private val executionContext: McpToolExecutionContext,
    private val fakeHttpRequestsFactory: FakeHttpRequestsFactory,
    private val urlMapping: UrlMapping
) : RestApiClient {

    override suspend fun get(path: String, query: String): RestApiResponse {
        return withContext(Dispatchers.IO) {
            executeRequest(HttpMethod.GET, path, query)
        }
    }

    override suspend fun post(path: String, query: String, body: String): RestApiResponse {
        return withContext(Dispatchers.IO) {
            executeRequest(HttpMethod.POST, path, query, body)
        }
    }

    override suspend fun put(path: String, query: String, body: String): RestApiResponse {
        return withContext(Dispatchers.IO) {
            executeRequest(HttpMethod.PUT, path, query, body)
        }
    }

    override suspend fun delete(path: String, query: String): RestApiResponse {
        return withContext(Dispatchers.IO) {
            executeRequest(HttpMethod.DELETE, path, query)
        }
    }

    private suspend fun executeRequest(
        method: HttpMethod,
        path: String,
        query: String,
        body: String? = null
    ): RestApiResponse {
        val user = executionContext.currentUser()
            ?: throw RestApiException(401, "Unauthorized", "No authenticated user in context")

        val controller = urlMapping.handlerMap["/app/rest/**"] as? BaseController
            ?: throw RestApiException(503, "Service Unavailable", "REST API controller not available")

        return try {
            val request = fakeHttpRequestsFactory.get(path, RestToolUtils.sanitizeQuery(query))
            request.setMethod(method.name)
            request.setHeader("Accept", "application/json, text/plain, */*")
            request.setAttribute("INTERNAL_REQUEST", true)
            SessionUser.setUser(request, user)

            if (body != null) {
                request.setInputStream(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
                request.setHeader("Content-Type", inferContentType(method, body))
            }

            val response = FakeHttpServletResponse()
            try {
                controller.handleRequestInternal(request, response)
            } catch (e: Exception) {
                throw RestApiException(400, "Bad Request", e.message ?: "Internal error")
            }

            val statusCode = if (response.status == 0) 200 else response.status
            val responseBody = response.returnedContent ?: ""
            if (statusCode >= 400) {
                throw RestApiException(
                    statusCode = statusCode,
                    statusText = statusText(statusCode),
                    message = extractErrorMessage(responseBody, statusCode)
                )
            }

            RestApiResponse(body = responseBody, statusCode = statusCode)
        } catch (e: RestApiException) {
            throw e
        } catch (e: Throwable) {
            throw RestApiException(500, "Internal Server Error", e.message ?: "Internal error")
        }
    }

    private fun inferContentType(method: HttpMethod, body: String): String {
        if (method == HttpMethod.POST) return "application/json"

        val trimmed = body.trim()
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            "application/json"
        } else {
            "text/plain"
        }
    }

    private fun statusText(statusCode: Int): String {
        return when (statusCode) {
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            415 -> "Unsupported Media Type"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "HTTP $statusCode"
        }
    }

    private fun extractErrorMessage(responseBody: String, statusCode: Int): String {
        if (responseBody.isBlank()) return statusText(statusCode)

        val parsed = RestToolUtils.parseJsonOrNull(responseBody)
        if (parsed is kotlinx.serialization.json.JsonObject) {
            val preferredKeys = listOf("message", "error", "description")
            preferredKeys.firstNotNullOfOrNull { key ->
                parsed[key]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
            }?.let { return it }
        }

        return responseBody.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(500)
            ?: statusText(statusCode)
    }
}
