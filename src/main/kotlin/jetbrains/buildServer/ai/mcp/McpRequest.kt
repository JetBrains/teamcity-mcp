package jetbrains.buildServer.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Pre-parsed MCP request data extracted from the raw JSON-RPC body.
 */
class McpRequest private constructor(
    val body: String,
    val method: String?,
    val requestId: RequestId?,
    val clientInfo: String?,
    val isInitialize: Boolean,
    val isRequest: Boolean
) {
    companion object {
        /**
         * Parse the raw JSON body into an [McpRequest].
         *
         * @throws ResponseStatusException (400) if the body is not valid JSON.
         */
        fun parse(body: String): McpRequest {
            val messageJson = try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: Throwable) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid JSON-RPC message",
                    e
                )
            }

            val method = extractMethod(messageJson)
            val requestId = extractRequestId(messageJson)
            val clientInfo = extractClientInfo(messageJson)

            val hasId = messageJson.containsKey("id")
            val hasMethod = messageJson.containsKey("method")
            val isInitialize = hasId && hasMethod && method == "initialize"
            val isRequest = hasId && hasMethod

            return McpRequest(
                body = body,
                method = method,
                requestId = requestId,
                clientInfo = clientInfo,
                isInitialize = isInitialize,
                isRequest = isRequest
            )
        }

        private fun extractMethod(messageJson: JsonObject): String? = try {
            messageJson["method"]?.jsonPrimitive?.content
        } catch (_: Throwable) {
            null
        }

        private fun extractRequestId(messageJson: JsonObject): RequestId? {
            val idElement = messageJson["id"] ?: return null
            if (idElement !is kotlinx.serialization.json.JsonPrimitive) return null
            return if (idElement.isString) {
                RequestId(idElement.content)
            } else {
                val longVal = idElement.content.toLongOrNull() ?: return null
                RequestId(longVal)
            }
        }

        private fun extractClientInfo(messageJson: JsonObject): String? = try {
            messageJson["params"]?.jsonObject?.get("clientInfo")?.toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Get the request ID, throwing 400 if missing or invalid.
     */
    fun requireRequestId(context: String = "request"): RequestId {
        return requestId ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Missing or invalid 'id' in $context"
        )
    }

    /**
     * Human-readable display value for the request ID.
     */
    fun requestIdDisplay(): String = when (val id = requestId) {
        is RequestId.StringId -> id.value
        is RequestId.NumberId -> id.value.toString()
        null -> "<none>"
    }
}
