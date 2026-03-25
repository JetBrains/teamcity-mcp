package jetbrains.buildServer.ai.mcp.framework.e2e

import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Helpers for asserting FUS (Feature Usage Statistics) events in the TeamCity server log.
 *
 * Requires the following internal properties on the test server:
 *   - `teamcity.internal.fus.debugToLogs=true` — logs FUS events to teamcity-server.log
 *   - `teamcity.internal.fus.flushInterval=5000` — flush every 5s instead of default 10min
 *
 * Also requires `TC_HOME` system property or env var pointing to the TeamCity installation.
 *
 * When enabled, the FUS core logs at WARN level:
 *   `WARN - jetbrains.buildServer.SERVER - Sending to FUS: { ... }`
 */
object FusLogAssertions {

    /** Log marker emitted by TeamCity FUS core at WARN level when debugToLogs=true. */
    private const val FUS_LOG_MARKER = "jetbrains.buildServer.SERVER - Sending to FUS: {\"events\":"

    /** Max time to wait for FUS flush (should be > flushInterval). */
    private const val MAX_WAIT_MS = 15_000L
    private const val POLL_INTERVAL_MS = 1_000L

    /**
     * Returns the TC server log file, or null if TC_HOME is not set.
     */
    fun serverLogFile(): File? {
        val tcHome = System.getProperty("TC_HOME") ?: System.getenv("TC_HOME") ?: return null
        return File(tcHome, "logs/teamcity-server.log").takeIf { it.exists() }
    }

    /**
     * Reads FUS log lines from the server log.
     */
    fun fusLines(): List<String> {
        val logFile = serverLogFile() ?: return emptyList()
        return logFile.readLines().filter { FUS_LOG_MARKER in it }
    }

    /**
     * Assert that the server log contains FUS entries for the given event IDs.
     * Waits up to [MAX_WAIT_MS] for the FUS flush to happen.
     *
     * @param eventIds  event IDs to check for (e.g. "ai.mcp.session.requested")
     * @param message   assertion failure message prefix
     */
    fun assertFusEventsLogged(vararg eventIds: String, message: String = "FUS events") {
        val logFile = serverLogFile()
        assertTrue(logFile != null && logFile.exists(),
            "$message: TC_HOME not set or server log not found. Set TC_HOME to the TeamCity installation directory.")

        // Wait for FUS flush — events are batched and written periodically
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        var fusLogLines: List<String> = emptyList()
        var allFound = false

        while (System.currentTimeMillis() < deadline) {
            fusLogLines = fusLines()
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
}
