package jetbrains.buildServer.ai.mcp

import jetbrains.buildServer.ai.mcp.framework.TestMcpClient
import jetbrains.buildServer.ai.mcp.framework.TcServerConfig
import org.junit.jupiter.api.TestInstance

/**
 * Base class for MCP integration tests.
 *
 * Reads the target server from system properties or environment variables:
 *   - `TC_SERVER_URL`   — e.g. "http://localhost:8111"  (required)
 *   - `TC_SERVER_TOKEN` — permanent Bearer token         (required)
 *
 * Run with:
 * ```
 * ./gradlew integrationTest -DTC_SERVER_URL=http://localhost:8111 -DTC_SERVER_TOKEN=eyJ...
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class McpIntegrationTestBase {

    val serverConfig: TcServerConfig by lazy {
        val url   = prop("TC_SERVER_URL")   ?: error("TC_SERVER_URL system property or env var is required")
        val token = prop("TC_SERVER_TOKEN") ?: error("TC_SERVER_TOKEN system property or env var is required")
        TcServerConfig(baseUrl = url, bearerToken = token)
    }

    /** Creates a fresh [TestMcpClient] bound to the configured server. */
    fun mcpClient(): TestMcpClient = TestMcpClient(serverConfig)

    private fun prop(name: String): String? = System.getProperty(name) ?: System.getenv(name)
}
