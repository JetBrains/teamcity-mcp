package jetbrains.buildServer.ai.mcp.tools.rest.impl

import jetbrains.buildServer.ServerUrlProvider
import jetbrains.buildServer.ai.mcp.tools.rest.RestApiClient
import jetbrains.buildServer.ai.mcp.tools.rest.RestApiResponse
import jetbrains.buildServer.serverSide.IOGuard
import jetbrains.buildServer.util.FuncThrow
import jetbrains.buildServer.util.HTTPRequestBuilder
import jetbrains.buildServer.util.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component

/**
 * REST API client that makes real HTTP requests to the local TeamCity server.
 */
@Component
class RestApiClientImpl(
    private val executionContext: McpToolExecutionContext,
    private val requestHandler: HTTPRequestBuilder.RequestHandler,
    private val serverUrlProvider: ServerUrlProvider
) : RestApiClient {

    override suspend fun get(path: String, query: String): RestApiResponse =
        executeRequest(HttpMethod.GET, path, query)

    override suspend fun post(path: String, query: String, body: String): RestApiResponse =
        executeRequest(HttpMethod.POST, path, query, body)

    private suspend fun executeRequest(
        method: HttpMethod,
        path: String,
        query: String,
        body: String? = null
    ): RestApiResponse {
        val forwardContext = executionContext.currentServletForwardContext()
            ?: error("Servlet forwarding context is not available for this tool execution")

        val originalRequest = forwardContext.request
        val baseUrl = serverUrlProvider.rootUrl.trimEnd('/')
        val url = if (query.isBlank()) "$baseUrl$path" else "$baseUrl$path?$query"

        val builder = HTTPRequestBuilder.request(url)
            .withMethod(method)
            .withHeader("Accept", "application/json, text/plain")
            .withUserAgent("mcp-rest-client")
            .allowNonSecureConnection(true)

        if (body != null) {
            builder.withPostStringEntity(body, "application/json", Charsets.UTF_8)
        }

        originalRequest.getHeader("Authorization")?.let {
            builder.withHeader("Authorization", it)
        }

        val response = withContext(Dispatchers.IO) {
            IOGuard.allowNetworkCall(FuncThrow {
                requestHandler.doSyncRequest(builder.build())
            })
        }

        return try {
            RestApiResponse(
                body = response.bodyAsString ?: "",
                statusCode = response.statusCode
            )
        } finally {
            response.close()
        }
    }
}
