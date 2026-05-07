package jetbrains.buildServer.ai.mcp.tools.rest.impl

data class RequestData(
    val requestAttributes: Map<String, Any?> = emptyMap(),
    val sessionAttributes: Map<String, Any?> = emptyMap()
)