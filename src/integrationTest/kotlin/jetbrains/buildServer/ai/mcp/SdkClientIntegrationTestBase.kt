package jetbrains.buildServer.ai.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import org.junit.jupiter.api.TestInstance

/**
 * Base class for SDK-client-based integration tests.
 *
 * Provides [createSdkClient] which creates a fully-initialized [Client]
 * using the official MCP Kotlin SDK's Streamable HTTP transport.
 *
 * Each client gets its own Ktor [HttpClient] and MCP session.
 * Callers must call [Client.close] when done.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SdkClientIntegrationTestBase : McpIntegrationTestBase() {

    /**
     * Creates a connected SDK [Client] (initialize handshake already done).
     *
     * The caller is responsible for calling [Client.close] to terminate the session.
     */
    suspend fun createSdkClient(): Client {
        val httpClient = HttpClient(CIO) {
            install(SSE)
        }
        return httpClient.mcpStreamableHttp(
            url = serverConfig.mcpUrl,
            requestBuilder = {
                headers.append("Authorization", "Bearer ${serverConfig.bearerToken}")
                headers.append("MCP-Protocol-Version", "2025-11-25")
            }
        )
    }
}
