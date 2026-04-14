package jetbrains.buildServer.ai.mcp.framework.e2e

import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Helpers for asserting MCP FUS (Feature Usage Statistics) events in teamcity-ai.log and teamcity-server.log.
 * Requires `TC_HOME` system property or env var pointing to the TeamCity installation.
 */
object FusLogAssertions {

    /** Log marker emitted by FusMcpEventHandler before calling fusRegistry.logEvent(). */
    private const val MCP_FUS_LOG_MARKER = "Sending FUS event:"

    /** Log marker emitted by TeamCity FUS core at WARN level when debugToLogs=true. */
    private const val FUS_LOG_MARKER = "jetbrains.buildServer.SERVER - Sending to FUS: {\"events\":"

    /** Max time to wait for FUS flush (should be > flushInterval). */
    private const val MAX_WAIT_MS = 15_000L
    private const val POLL_INTERVAL_MS = 1_000L

    private fun logFile(name: String): File? {
        val tcHome = System.getProperty("TC_HOME") ?: System.getenv("TC_HOME") ?: return null
        return File(tcHome, "logs/$name").takeIf { it.exists() }
    }

    private fun readLines(name: String): List<String> =
        logFile(name)?.readLines() ?: emptyList()

    /**
     * Assert that teamcity-ai.log contains no "skipping MCP FUS event logging" messages,
     * which would indicate that FUS event class names failed to resolve at runtime.
     */
    fun assertNoFusClassLoadingErrors() {
        val errorLines = readLines("teamcity-ai.log")
            .filter { "skipping MCP FUS event logging" in it }
        assertTrue(
            errorLines.isEmpty(),
            "FUS event classes must be loadable at runtime, but teamcity-ai.log contains:\n${errorLines.joinToString("\n")}"
        )
    }

    /**
     * Assert that the server log contains FUS entries for the given event IDs.
     * Waits up to [MAX_WAIT_MS] for the FUS flush to happen.
     *
     * @param eventIds  event IDs to check for (e.g. "ai.mcp.session.requested")
     * @param message   assertion failure message prefix
     */
    fun assertFusEventsLogged(vararg eventIds: String, message: String = "FUS events") {
        val logFile = logFile("teamcity-server.log")
        assertTrue(logFile != null && logFile.exists(),
            "$message: TC_HOME not set or server log not found. Set TC_HOME to the TeamCity installation directory.")

        // Wait for FUS flush — events are batched and written periodically
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        var fusLogLines: List<String> = emptyList()
        var allFound = false

        while (System.currentTimeMillis() < deadline) {
            fusLogLines = readLines("teamcity-server.log").filter { FUS_LOG_MARKER in it }
            if (fusLogLines.isNotEmpty()) {
                val allFusText = fusLogLines.joinToString("\n")
                allFound = eventIds.all { allFusText.contains(it) }
                if (allFound) break
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        assertTrue(fusLogLines.isNotEmpty(),
            "$message: No FUS log entries found in ${logFile!!.absolutePath} after ${MAX_WAIT_MS}ms. " +
                    "Ensure teamcity.internal.fus.debugToLogs=true and a short flushInterval are set.")

        val allFusText = fusLogLines.joinToString("\n")
        for (eventId in eventIds) {
            assertTrue(allFusText.contains(eventId),
                "$message: FUS event '$eventId' not found in server log after ${MAX_WAIT_MS}ms.\n" +
                        "FUS lines (last 20):\n${fusLogLines.takeLast(20).joinToString("\n")}")
        }
    }

    /**
     * Assert that teamcity-ai.log contains MCP FUS event entries for the given event IDs.
     *
     * @param eventClasses  event IDs to check for (e.g. "ai.mcp.session.requested")
     * @param message   assertion failure message prefix
     */
    fun assertMcpFusEventsLogged(vararg eventClasses: String, message: String = "FUS events") {
        val logFile = logFile("teamcity-ai.log")
        assertTrue(logFile != null,
            "$message: TC_HOME not set or teamcity-ai.log not found. " +
                "Ensure DEBUG logging is enabled for jetbrains.buildServer.ai.")

        val fusLines = readLines("teamcity-ai.log").filter { MCP_FUS_LOG_MARKER in it }
        assertTrue(fusLines.isNotEmpty(),
            "$message: No '$MCP_FUS_LOG_MARKER' entries found in ${logFile!!.absolutePath}. " +
                "Ensure DEBUG logging is enabled for jetbrains.buildServer.ai.")

        val allFusText = fusLines.joinToString("\n")
        for (className in eventClasses) {
            assertTrue(allFusText.contains(className),
                "$message: FUS event '$className' not found in teamcity-ai.log.\n" +
                    "FUS lines (last 20):\n${fusLines.takeLast(20).joinToString("\n")}")
        }
    }
}
