package jetbrains.buildServer.ai.mcp.resources

interface McpResource {
    val uri: String
    val name: String
    val description: String
    val mimeType: String
        get() = "text/markdown"

    val shortName: String

    fun read(): String
}
