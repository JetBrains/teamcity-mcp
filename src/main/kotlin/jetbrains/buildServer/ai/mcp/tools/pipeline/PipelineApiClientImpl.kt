package jetbrains.buildServer.ai.mcp.tools.pipeline

import jetbrains.buildServer.ai.mcp.tools.rest.RestApiResponse
import jetbrains.buildServer.ai.mcp.tools.rest.RestToolUtils
import jetbrains.buildServer.ai.mcp.tools.rest.impl.McpToolExecutionContext
import jetbrains.buildServer.controllers.fakes.FakeHttpRequestsFactory
import jetbrains.buildServer.controllers.fakes.FakeHttpServletResponse
import jetbrains.buildServer.maintenance.TeamCityDispatcherServlet
import jetbrains.buildServer.util.http.HttpMethod
import jetbrains.buildServer.web.util.SessionUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream

@Component
class PipelineApiClientImpl(
    private val executionContext: McpToolExecutionContext,
    private val fakeHttpRequestsFactory: FakeHttpRequestsFactory
) : PipelineApiClient {
    companion object {
        private const val APP_PREFIX = "/app"
    }

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

        val dispatcher = TeamCityDispatcherServlet.webDispatcherServlet ?: return RestApiResponse(
            body = "Web dispatcher is not available",
            statusCode = 503
        )

        return try {
            val request = fakeHttpRequestsFactory.get(toDispatcherPath(path), RestToolUtils.sanitizeQuery(query))
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
                dispatcher.service(request, response)
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

    private fun toDispatcherPath(path: String): String =
        if (path.startsWith(APP_PREFIX)) path.removePrefix(APP_PREFIX) else path
}
