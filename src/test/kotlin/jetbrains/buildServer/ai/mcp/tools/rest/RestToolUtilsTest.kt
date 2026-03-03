package jetbrains.buildServer.ai.mcp.tools.rest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RestToolUtilsTest {

    @Nested
    inner class SanitizeQuery {

        @Test
        fun `encodes plus as percent 2B`() {
            assertEquals(
                "locator=sinceDate:20250101T000000%2B0000",
                RestToolUtils.sanitizeQuery("locator=sinceDate:20250101T000000+0000")
            )
        }

        @Test
        fun `encodes space as percent 20`() {
            assertEquals(
                "locator=agent:(name:Default%20Agent-1)",
                RestToolUtils.sanitizeQuery("locator=agent:(name:Default Agent-1)")
            )
        }

        @Test
        fun `encodes both plus and space`() {
            assertEquals(
                "locator=agent:(name:My%20Agent),sinceDate:20250101T000000%2B0000",
                RestToolUtils.sanitizeQuery("locator=agent:(name:My Agent),sinceDate:20250101T000000+0000")
            )
        }

        @Test
        fun `preserves already encoded percent 2B`() {
            assertEquals(
                "locator=sinceDate:20250101T000000%2B0000",
                RestToolUtils.sanitizeQuery("locator=sinceDate:20250101T000000%2B0000")
            )
        }

        @Test
        fun `preserves already encoded percent 20`() {
            assertEquals(
                "locator=agent:(name:Default%20Agent)",
                RestToolUtils.sanitizeQuery("locator=agent:(name:Default%20Agent)")
            )
        }

        @Test
        fun `leaves plain queries unchanged`() {
            val query = "locator=buildType:(id:MyBuild),status:SUCCESS&fields=build(id,number)"
            assertEquals(query, RestToolUtils.sanitizeQuery(query))
        }

        @Test
        fun `handles empty query`() {
            assertEquals("", RestToolUtils.sanitizeQuery(""))
        }

        @Test
        fun `encodes spaces in blank query`() {
            assertEquals("%20%20%20", RestToolUtils.sanitizeQuery("   "))
        }

        @Test
        fun `encodes multiple plus signs`() {
            assertEquals(
                "sinceDate:20250101T000000%2B0000,untilDate:20250201T000000%2B0000",
                RestToolUtils.sanitizeQuery("sinceDate:20250101T000000+0000,untilDate:20250201T000000+0000")
            )
        }

        @Test
        fun `encodes negative offset without changing minus`() {
            assertEquals(
                "locator=sinceDate:20250101T000000-0500",
                RestToolUtils.sanitizeQuery("locator=sinceDate:20250101T000000-0500")
            )
        }
    }
}
