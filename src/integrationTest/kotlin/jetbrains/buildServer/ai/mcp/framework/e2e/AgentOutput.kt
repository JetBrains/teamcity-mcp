package jetbrains.buildServer.ai.mcp.framework.e2e

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * Structured output from an AI agent CLI invocation.
 *
 * Parses NDJSON events produced by `--output-format stream-json` and provides
 * assertion helpers for validating tool discovery and execution.
 *
 * Claude Code NDJSON format:
 *   `{"type":"assistant","message":{"content":[{"type":"tool_use","name":"mcp__server__tool"}]}}`
 *
 * Note: Claude Code prefixes MCP tool names with `mcp__{serverName}__`.
 * The [assertToolCalled] helper matches both exact names and namespaced suffixes.
 */
data class AgentOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {

    /** All successfully parsed NDJSON events from stdout. */
    val events: List<JsonObject> by lazy {
        stdout.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    Json.parseToJsonElement(line).jsonObject
                } catch (_: Exception) {
                    null
                }
            }
    }

    /**
     * Names of MCP tools that the agent called (extracted from NDJSON events).
     *
     * These may be namespaced by the agent (e.g. `mcp__teamcity__introduce_yourself`).
     */
    val toolCalls: List<String> by lazy {
        val result = mutableListOf<String>()
        for (event in events) {
            // Claude Code: {"type":"assistant","message":{"content":[{"type":"tool_use","name":"X"}]}}
            event.contentBlocks()?.forEach { block ->
                if (block.strField("type") == "tool_use") {
                    block.strField("name")?.let { result += it }
                }
            }
            // Codex: {"type":"item.completed","item":{"type":"mcp_tool_call","tool":"X"}}
            event["item"]?.asObjectOrNull()?.let { item ->
                if (item.strField("type") == "mcp_tool_call") {
                    (item.strField("tool") ?: item.strField("name"))?.let { result += it }
                }
            }
            // Gemini: {"type":"tool_use","tool_name":"X"}
            if (event.strField("type") == "tool_use") {
                event.strField("tool_name")?.let { result += it }
            }
        }
        result
    }

    /** The final `"type":"result"` event, if present. */
    val resultEvent: JsonObject? by lazy {
        events.lastOrNull { it.strField("type") == "result" }
    }

    /** Full concatenated text from all assistant message text blocks and result events. */
    val fullText: String by lazy {
        buildString {
            for (event in events) {
                // Claude Code: {"type":"assistant","message":{"content":[{"type":"text","text":"..."}]}}
                event.contentBlocks()?.forEach { block ->
                    if (block.strField("type") == "text") {
                        block.strField("text")?.let { append(it).append("\n") }
                    }
                }
                // Codex: {"type":"item.completed","item":{"type":"agent_message","text":"..."}}
                event["item"]?.asObjectOrNull()?.let { item ->
                    if (item.strField("type") == "agent_message") {
                        item.strField("text")?.let { append(it).append("\n") }
                    }
                }
                // Gemini: {"type":"message","role":"assistant","content":"..."}
                if (event.strField("type") == "message" && event.strField("role") == "assistant") {
                    event.strField("content")?.let { append(it).append("\n") }
                }
                event.strField("result")?.let { append(it).append("\n") }
            }
        }
    }

    /**
     * Tool names available to the agent, extracted from the init/system event.
     *
     * Works across agent formats:
     *  - Claude Code: `{"type":"system","subtype":"init","tools":[...]}`
     *  - Gemini CLI:  `{"type":"init","tools":[...]}`
     *  - Codex:       (parsed from events if present)
     */
    val availableTools: Set<String> by lazy {
        val initEvent = events.firstOrNull {
            (it.strField("type") == "system" && it.strField("subtype") == "init") ||
                it.strField("type") == "init"
        }
        initEvent?.get("tools")?.asArrayOrNull()
            ?.mapNotNull { try { it.jsonPrimitive.content } catch (_: Exception) { null } }
            ?.toSet()
            ?: emptySet()
    }

    /**
     * True when the output indicates the external AI provider API returned a
     * transient error (overload, rate-limit, internal server error).
     * These are provider-side issues, not MCP plugin problems.
     */
    val isExternalApiError: Boolean by lazy {
        val combined = stdout + "\n" + stderr
        EXTERNAL_API_ERROR_PATTERNS.any { it.containsMatchIn(combined) }
    }

    // -- diagnostics --

    /** Print all captured output to stdout for debugging. */
    fun dump(label: String = "AgentOutput"): AgentOutput {
        println("  ┌── $label ──")
        println("  │ Exit code: $exitCode")
        println("  │ Events: ${events.size}, Tool calls: $toolCalls")
        if (stdout.isNotBlank()) {
            println("  │ ── stdout ──")
            stdout.lines().forEach { println("  │ $it") }
        }
        if (stderr.isNotBlank()) {
            println("  │ ── stderr ──")
            stderr.lines().forEach { println("  │ $it") }
        }
        if (fullText.isNotBlank()) {
            println("  │ ── fullText ──")
            fullText.lines().forEach { println("  │ $it") }
        }
        println("  └──")
        return this
    }

    // -- assertions --

    /**
     * Skip the test when the output shows an external AI API error
     * (e.g. Anthropic 529 Overloaded, Gemini 500 Internal, rate limits).
     * Must be called before hard assertions like [assertExitCode].
     */
    fun assumeExternalApiAvailable(label: String = ""): AgentOutput {
        Assumptions.assumeFalse(
            isExternalApiError,
            "${if (label.isNotEmpty()) "$label: " else ""}" +
                "External AI API error — skipping (not an MCP plugin issue). " +
                "stderr: ${stderr.take(500)}"
        )
        return this
    }

    fun assertExitCode(expected: Int, label: String = "command"): AgentOutput {
        assertEquals(expected, exitCode,
            "$label exited with $exitCode (expected $expected).\nstderr:\n${stderr.take(2000)}")
        return this
    }

    fun assertOutputContains(text: String, message: String = ""): AgentOutput {
        val haystack = stdout + "\n" + fullText
        assertTrue(haystack.contains(text),
            "${if (message.isNotEmpty()) "$message: " else ""}Output must contain '$text'.\n" +
                "stdout (first 3000 chars):\n${stdout.take(3000)}")
        return this
    }

    /**
     * Assert that a tool with the given name was called.
     *
     * Matches both exact name and MCP-namespaced names (e.g. `mcp__server__toolName`
     * matches `toolName`).
     */
    fun assertToolCalled(toolName: String): AgentOutput {
        assertTrue(
            toolCalls.any { it == toolName || it.endsWith("__$toolName") },
            "Tool '$toolName' must have been called. Actual tool calls: $toolCalls\n" +
                "stdout (first 3000 chars):\n${stdout.take(3000)}")
        return this
    }

    fun assertNoErrors(): AgentOutput {
        assertTrue(stderr.isBlank(),
            "Expected no errors in stderr but got:\n${stderr.take(2000)}")
        return this
    }

    /**
     * Assert that the output contains at least one of the given texts (case-insensitive).
     *
     * Useful for LLM-generated output where the exact format is non-deterministic.
     */
    fun assertOutputContainsAny(vararg texts: String, message: String = ""): AgentOutput {
        val haystack = (stdout + "\n" + fullText).lowercase()
        assertTrue(texts.any { haystack.contains(it.lowercase()) },
            "${if (message.isNotEmpty()) "$message: " else ""}Output must contain at least one of: " +
                "${texts.joinToString(", ") { "'$it'" }}.\n" +
                "stdout (first 3000 chars):\n${stdout.take(3000)}\n" +
                "fullText (first 3000 chars):\n${fullText.take(3000)}")
        return this
    }

    // -- private helpers --

    private fun JsonObject.contentBlocks(): List<JsonObject>? =
        this["message"]?.asObjectOrNull()
            ?.get("content")?.asArrayOrNull()
            ?.mapNotNull { it.asObjectOrNull() }

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray

    private fun JsonObject.strField(key: String): String? =
        try { this[key]?.jsonPrimitive?.content } catch (_: Exception) { null }

    companion object {
        /** Patterns that identify transient external AI provider API errors. */
        internal val EXTERNAL_API_ERROR_PATTERNS = listOf(
            Regex("overloaded_error"),
            Regex("\"status\":\\s*529"),
            Regex("got status: INTERNAL"),
            Regex("rate.?limit", RegexOption.IGNORE_CASE),
            Regex("\"code\":\\s*429"),
            Regex("\"code\":\\s*500,\\s*\"message\":\\s*\"Internal error"),
            Regex("\"code\":\\s*503"),
            Regex("\"status\":\\s*\"INTERNAL\""),
        )
    }
}
