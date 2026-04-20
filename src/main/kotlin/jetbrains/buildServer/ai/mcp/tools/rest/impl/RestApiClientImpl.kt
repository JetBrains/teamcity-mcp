package jetbrains.buildServer.ai.mcp.tools.rest.impl

import jetbrains.buildServer.ai.mcp.tools.rest.RestApiClient
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
            executeRequest( HttpMethod.GET, path, query)
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
        val user = executionContext.currentUser() ?: return RestApiResponse(
            body = "No authenticated user in context",
            statusCode = 401
        )

        val controller = urlMapping.handlerMap["/app/rest/**"] as? BaseController ?: return RestApiResponse(
            body = "REST API controller not available", statusCode = 503
        )

        return try {
            val request = fakeHttpRequestsFactory.get(path, RestToolUtils.sanitizeQuery(query))
            request.setMethod(method.name)
            request.setHeader("Accept", "application/json")
            request.setAttribute("INTERNAL_REQUEST", true)
            SessionUser.setUser(request, user)

            if (body != null) {
                request.setInputStream(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
                request.setHeader("Content-Type", "application/json")
            }

            val response = FakeHttpServletResponse()
            try {
                controller.handleRequestInternal(request, response)
            } catch (e: Exception) {
                return RestApiResponse(
                    body = e.message ?: "Internal error",
                    statusCode = 400
                )
            }

            val statusCode = if (response.status == 0) 200 else response.status
            RestApiResponse(
                body = response.returnedContent ?: "",
                statusCode = statusCode
            )
        } catch (e: Throwable) {
            RestApiResponse(body = e.message ?: "Internal error", statusCode = 500)
        }
    }
}
