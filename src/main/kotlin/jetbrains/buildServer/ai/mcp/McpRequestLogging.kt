package jetbrains.buildServer.ai.mcp

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.TeamCityProperties
import javax.servlet.http.HttpServletRequest

private val SENSITIVE_HEADERS = setOf(
    "authorization", "cookie", "set-cookie", "x-tc-csrf-token"
)

internal fun sanitizeHeadersForLogging(request: HttpServletRequest): Map<String, String> = buildMap {
    request.headerNames?.toList()?.forEach { name ->
        val value = if (name.lowercase() in SENSITIVE_HEADERS) "*****" else request.getHeader(name)
        put(name, value)
    }
}

internal fun logIncomingRequest(logger: Logger, method: String, request: HttpServletRequest, body: String? = null) {
    if (!logger.isDebugEnabled) return
    val headers = sanitizeHeadersForLogging(request)
    val bodyForLog = when {
        body != null && isBodyLoggingEnabled() -> body
        body == null && isBodyLoggingEnabled() -> readBodyForLogging(request)
        else -> null
    }
    logger.debug(
        "$method request. remoteAddr=${request.remoteAddr}, " +
            "headers=$headers" +
            (if (bodyForLog != null) ", body=$bodyForLog" else "")
    )
}

private fun isBodyLoggingEnabled(): Boolean =
    TeamCityProperties.getBoolean("teamcity.ai.mcp.log.requestBody")

private fun readBodyForLogging(request: HttpServletRequest): String? {
    return try {
        val body = request.reader.readText()
        body.ifEmpty { null }
    } catch (_: Throwable) {
        null
    }
}
