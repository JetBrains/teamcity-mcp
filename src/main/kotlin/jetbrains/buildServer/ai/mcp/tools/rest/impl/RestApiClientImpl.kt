package jetbrains.buildServer.ai.mcp.tools.rest.impl

import jetbrains.buildServer.ai.mcp.tools.rest.RestApiClient
import jetbrains.buildServer.ai.mcp.tools.rest.RestApiResponse
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.Collections
import javax.servlet.ReadListener
import javax.servlet.ServletInputStream
import javax.servlet.ServletOutputStream
import javax.servlet.WriteListener
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpServletResponseWrapper

@Component
class RestApiClientImpl(
    private val executionContext: McpToolExecutionContext
) : RestApiClient {

    override suspend fun get(path: String, query: String): RestApiResponse {
        val forwardContext = executionContext.currentServletForwardContext()
            ?: error("Servlet forwarding context is not available for this tool execution")

        val dispatcher = forwardContext.request.getRequestDispatcher(path)
            ?: error("RequestDispatcher is not available for path: $path")

        val forwardedRequest = ForwardedGetRequest(
            original = forwardContext.request,
            path = path,
            query = query
        )
        val forwardedResponse = CapturingResponse(forwardContext.response)

        dispatcher.forward(forwardedRequest, forwardedResponse)

        return RestApiResponse(
            body = forwardedResponse.bodyAsString(),
            statusCode = forwardedResponse.statusCode
        )
    }
}

private val MASKED_HEADERS = setOf("content-type", "content-length", "transfer-encoding")

private class ForwardedGetRequest(
    original: HttpServletRequest,
    private val path: String,
    private val query: String
) : HttpServletRequestWrapper(original) {
    override fun getMethod(): String = "GET"
    override fun getPathInfo(): String = path
    override fun getRequestURI(): String = path
    override fun getServletPath(): String = path
    override fun getQueryString(): String? = query.ifBlank { null }

    // Mask POST body so the forwarded request looks like a real GET
    override fun getContentType(): String? = null
    override fun getContentLength(): Int = -1
    override fun getContentLengthLong(): Long = -1L
    override fun getInputStream(): ServletInputStream = EmptyServletInputStream()
    override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(ByteArrayInputStream(ByteArray(0))))

    // Filter out POST-specific headers
    override fun getHeader(name: String): String? =
        if (name.lowercase() in MASKED_HEADERS) null else super.getHeader(name)

    override fun getHeaders(name: String): java.util.Enumeration<String> =
        if (name.lowercase() in MASKED_HEADERS) Collections.emptyEnumeration() else super.getHeaders(name)

    override fun getHeaderNames(): java.util.Enumeration<String> {
        val names = super.getHeaderNames()?.toList()?.filter { it.lowercase() !in MASKED_HEADERS } ?: emptyList()
        return Collections.enumeration(names)
    }

    override fun getIntHeader(name: String): Int =
        if (name.lowercase() in MASKED_HEADERS) -1 else super.getIntHeader(name)
}

private class EmptyServletInputStream : ServletInputStream() {
    override fun read(): Int = -1
    override fun isFinished(): Boolean = true
    override fun isReady(): Boolean = true
    override fun setReadListener(listener: ReadListener?) = Unit
}

private class CapturingResponse(
    original: HttpServletResponse
) : HttpServletResponseWrapper(original) {
    private val buffer = ByteArrayOutputStream()
    private val outputStream = CapturingServletOutputStream(buffer)
    private var writer: PrintWriter? = null
    private var contentTypeValue: String? = null
    private var characterEncodingValue: String = "UTF-8"
    var statusCode: Int = HttpServletResponse.SC_OK
        private set

    override fun setStatus(sc: Int) {
        statusCode = sc
    }

    override fun sendError(sc: Int) {
        statusCode = sc
    }

    override fun sendError(sc: Int, msg: String?) {
        statusCode = sc
        if (msg != null) {
            getWriter().write(msg)
        }
    }

    override fun setCharacterEncoding(charset: String?) {
        if (!charset.isNullOrBlank()) {
            characterEncodingValue = charset
        }
    }

    override fun getCharacterEncoding(): String = characterEncodingValue

    override fun setContentType(type: String?) {
        contentTypeValue = type
    }

    override fun getContentType(): String? = contentTypeValue

    override fun getOutputStream(): ServletOutputStream = outputStream

    override fun getWriter(): PrintWriter {
        if (writer == null) {
            writer = PrintWriter(OutputStreamWriter(buffer, characterEncodingValue), true)
        }
        return writer!!
    }

    fun bodyAsString(): String {
        writer?.flush()
        return buffer.toString(characterEncodingValue)
    }
}

private class CapturingServletOutputStream(
    private val out: ByteArrayOutputStream
) : ServletOutputStream() {
    override fun write(b: Int) {
        out.write(b)
    }

    override fun isReady(): Boolean = true

    override fun setWriteListener(listener: WriteListener?) = Unit
}
