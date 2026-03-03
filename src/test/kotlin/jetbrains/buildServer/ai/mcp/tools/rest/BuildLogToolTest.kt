package jetbrains.buildServer.ai.mcp.tools.rest

import io.mockk.every
import io.mockk.mockk
import jetbrains.buildServer.messages.Status
import jetbrains.buildServer.serverSide.BuildsManager
import jetbrains.buildServer.serverSide.SBuild
import jetbrains.buildServer.serverSide.buildLog.BuildLog
import jetbrains.buildServer.serverSide.buildLog.BuildLogReaderEx
import jetbrains.buildServer.serverSide.buildLog.LogMessage
import jetbrains.buildServer.serverSide.buildLog.LogMessageIterator
import jetbrains.buildServer.serverSide.buildLog.LogView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class BuildLogToolTest {

    private fun tool(buildsManager: BuildsManager? = null) = BuildLogTool(buildsManager)

    /** Creates a mocked LogMessage. */
    private fun logMsg(
        index: Int,
        text: String,
        status: Status = Status.NORMAL
    ): LogMessage = mockk {
        every { this@mockk.index } returns index
        every { this@mockk.text } returns text
        every { this@mockk.status } returns status
    }

    /** Sample messages with mixed statuses. */
    private val mixedMessages = listOf(
        logMsg(0, "bt1", Status.NORMAL),
        logMsg(2, "Starting build...", Status.NORMAL),
        logMsg(3, "Compile warning", Status.WARNING),
        logMsg(4, "Compilation error!", Status.ERROR),
        logMsg(5, "Test failed", Status.FAILURE)
    )

    /** Wraps a list of LogMessage mocks into a mocked LogMessageIterator. */
    private fun mockIterator(messages: List<LogMessage>): LogMessageIterator {
        val backing = messages.iterator()
        return mockk {
            every { hasNext() } answers { backing.hasNext() }
            every { next() } answers { backing.next() }
        }
    }

    /** Creates a BuildsManager mock that returns the given messages for the given buildId. */
    private fun managerWithMessages(
        buildId: Long = 123,
        messages: List<LogMessage> = mixedMessages
    ): BuildsManager {
        val buildLog = mockk<BuildLogReaderEx>(moreInterfaces = arrayOf(BuildLog::class)) {
            every { createIterator(any(), eq(LogView.LINEAR)) } answers {
                val startIdx = firstArg<Int>()
                mockIterator(messages.filter { it.index >= startIdx })
            }
        }
        val build = mockk<SBuild> {
            every { getBuildLog() } returns buildLog as BuildLog
        }
        return mockk {
            every { findBuildInstanceById(any()) } answers {
                if (firstArg<Long>() == buildId) build else null
            }
        }
    }

    /** Creates a BuildsManager that captures args passed to findBuildInstanceById and createIterator. */
    private class Captured {
        var buildId: Long? = null
        var startIndex: Int? = null
    }

    private fun capturingManager(
        messages: List<LogMessage> = emptyList()
    ): Pair<BuildsManager, Captured> {
        val captured = Captured()
        val buildLog = mockk<BuildLogReaderEx>(moreInterfaces = arrayOf(BuildLog::class)) {
            every { createIterator(any(), eq(LogView.LINEAR)) } answers {
                captured.startIndex = firstArg()
                val startIdx = firstArg<Int>()
                mockIterator(messages.filter { it.index >= startIdx })
            }
        }
        val build = mockk<SBuild> {
            every { getBuildLog() } returns buildLog as BuildLog
        }
        val manager = mockk<BuildsManager> {
            every { findBuildInstanceById(any()) } answers {
                captured.buildId = firstArg()
                build
            }
        }
        return Pair(manager, captured)
    }

    /** Helper to build arguments JsonObject with direct params. */
    private fun args(
        buildId: String? = null,
        filter: String? = null,
        start: String? = null,
        count: String? = null
    ) = buildJsonObject {
        buildId?.let { put("buildId", it) }
        filter?.let { put("filter", it) }
        start?.let { put("start", it) }
        count?.let { put("count", it) }
    }

    /** Returns the log body lines (after the header block). */
    private fun logLines(text: String): List<String> {
        val lines = text.lines()
        val emptyIdx = lines.indexOf("")
        return if (emptyIdx >= 0) lines.drop(emptyIdx + 1).filter { it.isNotEmpty() } else emptyList()
    }

    // -----------------------------------------------------------------------
    // Metadata
    // -----------------------------------------------------------------------

    @Test
    fun `name is teamcity_build_log`() {
        assertEquals("teamcity_build_log", tool().name)
    }

    @Test
    fun `schema declares buildId filter start and count properties`() {
        val props = tool().inputSchema.properties!!
        assertTrue(props.containsKey("buildId"))
        assertTrue(props.containsKey("filter"))
        assertTrue(props.containsKey("start"))
        assertTrue(props.containsKey("count"))
    }

    @Test
    fun `buildId is the only required parameter`() {
        assertEquals(listOf("buildId"), tool().inputSchema.required)
    }

    @Test
    fun `description mentions filtering and pagination`() {
        val desc = tool().description
        assertTrue(desc.contains("filter"))
        assertTrue(desc.contains("start") && desc.contains("count"))
    }

    // -----------------------------------------------------------------------
    // Input validation
    // -----------------------------------------------------------------------

    @Nested
    inner class InputValidation {

        @Test
        fun `execute without arguments returns error mentioning buildId`() = runBlocking {
            val result = tool(managerWithMessages()).execute(null)
            assertTrue(result.isError)
            assertTrue(result.text.contains("buildId"))
        }

        @Test
        fun `execute without buildId parameter returns error`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(filter = "errors"))
            assertTrue(result.isError)
            assertTrue(result.text.contains("buildId"))
        }

        @Test
        fun `execute with blank buildId returns error`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "  "))
            assertTrue(result.isError)
            assertTrue(result.text.contains("buildId"))
        }

        @Test
        fun `execute with non-numeric buildId returns error`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "abc"))
            assertTrue(result.isError)
            assertTrue(result.text.contains("numeric"))
        }

        @Test
        fun `execute with overflow buildId returns error`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "99999999999999999999"))
            assertTrue(result.isError)
            assertTrue(result.text.contains("numeric"))
        }

        @Test
        fun `execute without buildsManager returns error about availability`() = runBlocking {
            val result = tool(buildsManager = null).execute(args(buildId = "123"))
            assertTrue(result.isError)
            assertTrue(result.text.contains("not available"))
        }
    }

    // -----------------------------------------------------------------------
    // Service invocation
    // -----------------------------------------------------------------------

    @Nested
    inner class ServiceInvocation {

        @Test
        fun `passes buildId from arguments`() = runBlocking {
            val (manager, captured) = capturingManager(mixedMessages)
            tool(manager).execute(args(buildId = "456"))
            assertEquals(456L, captured.buildId)
        }

        @Test
        fun `uses default startIndex=0 when no start specified`() = runBlocking {
            val (manager, captured) = capturingManager(mixedMessages)
            tool(manager).execute(args(buildId = "123"))
            assertEquals(0, captured.startIndex)
        }

        @Test
        fun `passes custom start index`() = runBlocking {
            val (manager, captured) = capturingManager(mixedMessages)
            tool(manager).execute(args(buildId = "123", start = "10"))
            assertEquals(10, captured.startIndex)
        }

        @Test
        fun `negative start defaults to 0`() = runBlocking {
            val (manager, captured) = capturingManager(mixedMessages)
            tool(manager).execute(args(buildId = "123", start = "-5"))
            assertEquals(0, captured.startIndex)
        }
    }

    // -----------------------------------------------------------------------
    // Response formatting
    // -----------------------------------------------------------------------

    @Nested
    inner class ResponseFormatting {

        @Test
        fun `successful response is not error`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "123"))
            assertFalse(result.isError)
        }

        @Test
        fun `response starts with header line`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "123"))
            assertTrue(result.text.startsWith("--- Build log: 5 messages, end of log ---"))
        }

        @Test
        fun `header shows correct message count`() = runBlocking {
            val manager = managerWithMessages(messages = listOf(logMsg(0, "one"), logMsg(1, "two")))
            val result = tool(manager).execute(args(buildId = "123"))
            assertTrue(result.text.contains("2 messages"))
        }

        @Test
        fun `normal messages appear as plain text`() = runBlocking {
            val manager = managerWithMessages(messages = listOf(logMsg(0, "hello world")))
            val result = tool(manager).execute(args(buildId = "123"))
            val lines = logLines(result.text)
            assertEquals(1, lines.size)
            assertEquals("hello world", lines[0])
        }

        @Test
        fun `warning messages have WARNING prefix`() = runBlocking {
            val manager = managerWithMessages(messages = listOf(logMsg(0, "low disk", Status.WARNING)))
            val result = tool(manager).execute(args(buildId = "123"))
            val lines = logLines(result.text)
            assertEquals("[WARNING] low disk", lines[0])
        }

        @Test
        fun `failure messages have FAILURE prefix`() = runBlocking {
            val manager = managerWithMessages(messages = listOf(logMsg(0, "test failed", Status.FAILURE)))
            val result = tool(manager).execute(args(buildId = "123"))
            val lines = logLines(result.text)
            assertEquals("[FAILURE] test failed", lines[0])
        }

        @Test
        fun `error messages have ERROR prefix`() = runBlocking {
            val manager = managerWithMessages(messages = listOf(logMsg(0, "OOM", Status.ERROR)))
            val result = tool(manager).execute(args(buildId = "123"))
            val lines = logLines(result.text)
            assertEquals("[ERROR] OOM", lines[0])
        }

        @Test
        fun `unknown status messages appear as plain text`() = runBlocking {
            val manager = managerWithMessages(messages = listOf(logMsg(0, "mystery", Status.UNKNOWN)))
            val result = tool(manager).execute(args(buildId = "123"))
            val lines = logLines(result.text)
            assertEquals("mystery", lines[0])
        }

        @Test
        fun `mixed messages have correct prefixes`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "123"))
            val lines = logLines(result.text)
            assertEquals(5, lines.size)
            assertEquals("bt1", lines[0])
            assertEquals("Starting build...", lines[1])
            assertEquals("[WARNING] Compile warning", lines[2])
            assertEquals("[ERROR] Compilation error!", lines[3])
            assertEquals("[FAILURE] Test failed", lines[4])
        }

        @Test
        fun `end of log shown when all messages fit`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "123"))
            assertTrue(result.text.contains("end of log"))
        }

        @Test
        fun `end of log absent when more messages exist`() = runBlocking {
            val manager = managerWithMessages(messages = (0..200).map { logMsg(it, "msg $it") })
            val result = tool(manager).execute(args(buildId = "123", count = "10"))
            assertFalse(result.text.contains("end of log"))
        }

        @Test
        fun `next page start shown when more messages available`() = runBlocking {
            val manager = managerWithMessages(messages = (0..200).map { logMsg(it, "msg $it") })
            val result = tool(manager).execute(args(buildId = "123", count = "10"))
            assertTrue(result.text.contains("next page start: 10"))
        }

        @Test
        fun `next page start absent when all messages fit`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "123"))
            assertFalse(result.text.contains("next page start"))
        }

        @Test
        fun `empty log returns zero count header`() = runBlocking {
            val manager = managerWithMessages(messages = emptyList())
            val result = tool(manager).execute(args(buildId = "123"))
            assertTrue(result.text.contains("0 messages"))
            assertEquals(0, logLines(result.text).size)
        }

        @Test
        fun `notes shown when count exceeds max`() = runBlocking {
            val manager = managerWithMessages(messages = (0..2000).map { logMsg(it, "msg $it") })
            val result = tool(manager).execute(args(buildId = "123", count = "5000"))
            assertTrue(result.text.contains("reduced") || result.text.contains("${BuildLogTool.MAX_COUNT}"))
        }

        @Test
        fun `more messages note when not all fit`() = runBlocking {
            val manager = managerWithMessages(messages = (0..200).map { logMsg(it, "msg $it") })
            val result = tool(manager).execute(args(buildId = "123", count = "10"))
            assertTrue(result.text.lowercase().contains("more messages"))
        }
    }

    // -----------------------------------------------------------------------
    // Pagination
    // -----------------------------------------------------------------------

    @Nested
    inner class Pagination {

        @Test
        fun `default count returns up to DEFAULT_COUNT messages`() = runBlocking {
            val manager = managerWithMessages(messages = (0..200).map { logMsg(it, "msg $it") })
            val result = tool(manager).execute(args(buildId = "123"))
            assertTrue(result.text.contains("${BuildLogTool.DEFAULT_COUNT} messages"))
        }

        @Test
        fun `count is capped at MAX_COUNT`() = runBlocking {
            val manager = managerWithMessages(messages = (0..2000).map { logMsg(it, "msg $it") })
            val result = tool(manager).execute(args(buildId = "123", count = "5000"))
            assertTrue(result.text.contains("${BuildLogTool.MAX_COUNT} messages"))
        }

        @Test
        fun `start parameter offsets the iteration`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "123", start = "3"))
            val lines = logLines(result.text)
            // mixedMessages at index >= 3: "Compile warning" (3), "Compilation error!" (4), "Test failed" (5)
            assertEquals(3, lines.size)
            assertEquals("[WARNING] Compile warning", lines[0])
        }

        @Test
        fun `next page start allows sequential paging`() = runBlocking {
            val allMessages = (0..50).map { logMsg(it, "msg $it") }
            val manager = managerWithMessages(messages = allMessages)

            // Page 1
            val result1 = tool(manager).execute(args(buildId = "123", start = "0", count = "10"))
            val lines1 = logLines(result1.text)
            assertEquals(10, lines1.size)
            // Extract next page start from header
            val nextPageStart = Regex("""next page start: (\d+)""").find(result1.text)!!.groupValues[1]

            // Page 2
            val result2 = tool(manager).execute(args(buildId = "123", start = nextPageStart, count = "10"))
            val lines2 = logLines(result2.text)
            assertEquals(10, lines2.size)

            // Ensure no overlap
            assertTrue(lines1.toSet().intersect(lines2.toSet()).isEmpty())
        }
    }

    // -----------------------------------------------------------------------
    // Status filtering
    // -----------------------------------------------------------------------

    @Nested
    inner class StatusFiltering {

        @Test
        fun `no filter returns all messages`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "123"))
            assertEquals(5, logLines(result.text).size)
        }

        @Test
        fun `filter=all returns all messages same as no filter`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "123", filter = "all"))
            assertEquals(5, logLines(result.text).size)
        }

        @Test
        fun `unknown filter value returns all messages`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "123", filter = "debug"))
            assertEquals(5, logLines(result.text).size)
        }

        @Test
        fun `filter=errors returns only FAILURE and ERROR messages`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "123", filter = "errors"))
            val lines = logLines(result.text)
            assertEquals(2, lines.size)
            assertTrue(lines.all { it.startsWith("[ERROR]") || it.startsWith("[FAILURE]") })
        }

        @Test
        fun `filter=warnings returns WARNING FAILURE and ERROR messages`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "123", filter = "warnings"))
            val lines = logLines(result.text)
            assertEquals(3, lines.size)
            assertTrue(lines.all {
                it.startsWith("[WARNING]") || it.startsWith("[ERROR]") || it.startsWith("[FAILURE]")
            })
        }

        @Test
        fun `filter=errors with no matching messages returns empty`() = runBlocking {
            val manager = managerWithMessages(messages = listOf(logMsg(0, "OK", Status.NORMAL)))
            val result = tool(manager).execute(args(buildId = "123", filter = "errors"))
            assertEquals(0, logLines(result.text).size)
        }

        @Test
        fun `filter is case insensitive`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "123", filter = "ERRORS"))
            assertEquals(2, logLines(result.text).size)
        }

        @Test
        fun `filter note is added when status filtering applied`() = runBlocking {
            val result = tool(managerWithMessages()).execute(args(buildId = "123", filter = "errors"))
            assertTrue(result.text.contains("filter") || result.text.contains("FAILURE/ERROR"))
        }

        @Test
        fun `filtering respects count limit`() = runBlocking {
            val errorMessages = (0..99).map { logMsg(it, "error $it", Status.ERROR) }
            val manager = managerWithMessages(messages = errorMessages)
            val result = tool(manager).execute(args(buildId = "123", filter = "errors", count = "10"))
            assertEquals(10, logLines(result.text).size)
        }

        @Test
        fun `next page start with filter skips over non-matching messages`() = runBlocking {
            // Messages: NORMAL at 0-9, ERROR at 10, NORMAL at 11-19, ERROR at 20
            val messages = (0..20).map { i ->
                logMsg(i, "msg $i", if (i == 10 || i == 20) Status.ERROR else Status.NORMAL)
            }
            val manager = managerWithMessages(messages = messages)
            val result = tool(manager).execute(args(buildId = "123", filter = "errors", count = "1"))
            val lines = logLines(result.text)
            assertEquals(1, lines.size)
            assertEquals("[ERROR] msg 10", lines[0])
            assertTrue(result.text.contains("next page start"))
            val nextPageStart = Regex("""next page start: (\d+)""").find(result.text)!!.groupValues[1].toInt()
            assertTrue(nextPageStart > 10)
        }

        @Test
        fun `scan limit prevents unbounded iteration with filter`() = runBlocking {
            val messages = (0 until BuildLogTool.MAX_SCAN + 100).map { logMsg(it, "msg $it", Status.NORMAL) }
            val manager = managerWithMessages(messages = messages)
            val result = tool(manager).execute(args(buildId = "123", filter = "errors", count = "10"))
            assertEquals(0, logLines(result.text).size)
            assertTrue(result.text.contains("Scan limit"))
        }
    }

    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

    @Nested
    inner class ErrorHandling {

        @Test
        fun `build not found returns error with build id`() = runBlocking {
            val manager = mockk<BuildsManager> {
                every { findBuildInstanceById(any()) } returns null
            }
            val result = tool(manager).execute(args(buildId = "999"))
            assertTrue(result.isError)
            assertTrue(result.text.contains("999"))
        }

        @Test
        fun `log not available returns error when buildLog is not BuildLogReaderEx`() = runBlocking {
            val build = mockk<SBuild> {
                every { getBuildLog() } returns mockk<BuildLog>()
            }
            val manager = mockk<BuildsManager> {
                every { findBuildInstanceById(any()) } returns build
            }
            val result = tool(manager).execute(args(buildId = "123"))
            assertTrue(result.isError)
            assertTrue(result.text.contains("not available"))
        }

        @Test
        fun `service exception returns error result`() = runBlocking {
            val manager = mockk<BuildsManager> {
                every { findBuildInstanceById(any()) } throws RuntimeException("Disk read error")
            }
            val result = tool(manager).execute(args(buildId = "123"))
            assertTrue(result.isError)
            assertTrue(result.text.contains("Disk read error"))
        }

        @Test
        fun `iterator exception returns error result`() = runBlocking {
            val failingIterator = mockk<LogMessageIterator> {
                every { hasNext() } returns true
                every { next() } throws RuntimeException("Corrupt log")
            }
            val buildLog = mockk<BuildLogReaderEx>(moreInterfaces = arrayOf(BuildLog::class)) {
                every { createIterator(any(), eq(LogView.LINEAR)) } returns failingIterator
            }
            val build = mockk<SBuild> {
                every { getBuildLog() } returns buildLog as BuildLog
            }
            val manager = mockk<BuildsManager> {
                every { findBuildInstanceById(any()) } returns build
            }
            val result = tool(manager).execute(args(buildId = "123"))
            assertTrue(result.isError)
            assertTrue(result.text.contains("Corrupt log"))
        }
    }
}
