package jetbrains.buildServer.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import jetbrains.buildServer.ai.mcp.resources.BraveModeAwareMcpResource
import jetbrains.buildServer.ai.mcp.resources.McpResource
import jetbrains.buildServer.ai.mcp.tools.BraveModeAwareMcpTool
import jetbrains.buildServer.ai.mcp.tools.McpTool
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

private const val NAME = "TeamCity mcp server"
private const val VERSION = "dev"

@Service
class McpServerConfigurator(
    private val settingsService: SettingsService,
    @Autowired(required = false) tools: List<McpTool>?,
    @Autowired(required = false) resources: List<McpResource>?
) {

    private val allTools: List<McpTool> = tools ?: emptyList()
    private val allResources: List<McpResource> = resources ?: emptyList()

    fun configureServer(): Server {
        val enabledResources = getEnabledResources()

        val server = Server(
            Implementation(
                name = NAME,
                version = VERSION
            ),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    resources = if (enabledResources.isNotEmpty())
                        ServerCapabilities.Resources(listChanged = false)
                    else null,
                )
            )
        )

        for (tool in getEnabledTools()) {
            server.addTool(
                name = tool.name,
                description = tool.description,
                inputSchema = ToolSchema(
                    properties = tool.inputSchema.properties,
                    required = tool.inputSchema.required
                )
            ) { request ->
                try {
                    val result = tool.execute(request.arguments)
                    CallToolResult(
                        content = listOf(TextContent(result.text)),
                        isError = if (result.isError) true else null
                    )
                } catch (e: Throwable) {
                    CallToolResult(
                        content = listOf(TextContent(e.message ?: "Unknown error")),
                        isError = true
                    )
                }
            }
        }

        for (resource in enabledResources) {
            server.addResource(
                uri = resource.uri,
                name = resource.name,
                description = resource.description,
                mimeType = resource.mimeType
            ) { request ->
                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            text = resource.read(),
                            uri = request.params.uri,
                            mimeType = resource.mimeType
                        )
                    )
                )
            }
        }

        return server
    }

    internal fun getEnabledTools(): List<McpTool> {
        val enabledSet = settingsService.getEnabledToolNames()
        val braveEnabled = settingsService.isBraveModeEnabled()
        return allTools
            .filter { it.name in enabledSet }
            .filter { it !is BraveModeAwareMcpTool || it.brave == braveEnabled }
    }

    internal fun getEnabledResources(): List<McpResource> {
        val enabledSet = settingsService.getEnabledResourceNames()
        val braveEnabled = settingsService.isBraveModeEnabled()
        return allResources
            .filter { it.shortName in enabledSet }
            .filter { it !is BraveModeAwareMcpResource || it.brave == braveEnabled }
    }
}
