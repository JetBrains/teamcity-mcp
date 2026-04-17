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

/**
 * Marker for resources that have a brave/safe variant.
 *
 * The configurator uses [brave] to keep only the variant matching the server's current brave-mode
 * setting: `brave = true` resources appear only when brave mode is on, `brave = false` resources
 * only when it is off. Resources that do not implement this interface are always included.
 */
interface BraveModeAwareMcpResource : McpResource {
    val brave: Boolean
}
