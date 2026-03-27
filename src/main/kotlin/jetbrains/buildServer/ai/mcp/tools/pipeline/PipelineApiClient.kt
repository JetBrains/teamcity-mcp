package jetbrains.buildServer.ai.mcp.tools.pipeline

import jetbrains.buildServer.ai.mcp.tools.rest.RestApiResponse

interface PipelineApiClient {
    suspend fun get(path: String, query: String): RestApiResponse
    suspend fun post(path: String, query: String, body: String): RestApiResponse
}
