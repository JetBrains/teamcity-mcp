package jetbrains.buildServer.ai.mcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class McpProtocolVersionTest {

    // ----------------------------
    // validate() - Missing results
    // ----------------------------

    @Test
    fun `validate null returns Missing`() {
        val result = McpProtocolVersion.validate(null)
        assertTrue(result is McpProtocolVersion.ValidationResult.Missing)
    }

    @Test
    fun `validate empty string returns Missing`() {
        val result = McpProtocolVersion.validate("")
        assertTrue(result is McpProtocolVersion.ValidationResult.Missing)
    }

    @Test
    fun `validate blank string returns Missing`() {
        val result = McpProtocolVersion.validate("   ")
        assertTrue(result is McpProtocolVersion.ValidationResult.Missing)
    }

    // ----------------------------
    // validate() - Valid results
    // ----------------------------

    @Test
    fun `validate supported version 2025-11-25 returns Valid`() {
        val result = McpProtocolVersion.validate("2025-11-25")
        assertTrue(result is McpProtocolVersion.ValidationResult.Valid)
        assertEquals("2025-11-25", (result as McpProtocolVersion.ValidationResult.Valid).version)
    }

    @Test
    fun `validate supported version 2025-06-18 returns Valid`() {
        val result = McpProtocolVersion.validate("2025-06-18")
        assertTrue(result is McpProtocolVersion.ValidationResult.Valid)
        assertEquals("2025-06-18", (result as McpProtocolVersion.ValidationResult.Valid).version)
    }

    // ----------------------------
    // validate() - Invalid results
    // ----------------------------

    @Test
    fun `validate unsupported version returns Invalid`() {
        val result = McpProtocolVersion.validate("2025-03-26")
        assertTrue(result is McpProtocolVersion.ValidationResult.Invalid)
    }

    @Test
    fun `validate random string returns Invalid`() {
        val result = McpProtocolVersion.validate("random")
        assertTrue(result is McpProtocolVersion.ValidationResult.Invalid)
    }

    @Test
    fun `validate Invalid contains provided version in result`() {
        val result = McpProtocolVersion.validate("2025-03-26")
        assertTrue(result is McpProtocolVersion.ValidationResult.Invalid)
        assertEquals("2025-03-26", (result as McpProtocolVersion.ValidationResult.Invalid).providedVersion)
    }

    @Test
    fun `validate Invalid reason message contains supported versions`() {
        val result = McpProtocolVersion.validate("2025-03-26")
        assertTrue(result is McpProtocolVersion.ValidationResult.Invalid)
        assertTrue((result as McpProtocolVersion.ValidationResult.Invalid).reason.contains("2025-11-25"))
    }

    // ----------------------------
    // validate() - Missing details
    // ----------------------------

    @Test
    fun `validate Missing has correct default version`() {
        val result = McpProtocolVersion.validate(null)
        assertTrue(result is McpProtocolVersion.ValidationResult.Missing)
        assertEquals("2025-03-26", (result as McpProtocolVersion.ValidationResult.Missing).defaultVersion)
    }

    // ----------------------------
    // getSupportedVersions()
    // ----------------------------

    @Test
    fun `getSupportedVersions returns non-empty list`() {
        val versions = McpProtocolVersion.getSupportedVersions()
        assertNotNull(versions)
        assertTrue(versions.isNotEmpty())
    }

    @Test
    fun `getSupportedVersions contains 2025-11-25`() {
        val versions = McpProtocolVersion.getSupportedVersions()
        assertTrue(versions.contains("2025-11-25"))
    }

    @Test
    fun `getSupportedVersions contains 2025-06-18`() {
        val versions = McpProtocolVersion.getSupportedVersions()
        assertTrue(versions.contains("2025-06-18"))
    }

    // ----------------------------
    // isSupported()
    // ----------------------------

    @Test
    fun `isSupported with 2025-11-25 returns true`() {
        assertTrue(McpProtocolVersion.isSupported("2025-11-25"))
    }

    @Test
    fun `isSupported with 2025-06-18 returns true`() {
        assertTrue(McpProtocolVersion.isSupported("2025-06-18"))
    }

    @Test
    fun `isSupported with unsupported version returns false`() {
        assertFalse(McpProtocolVersion.isSupported("2025-03-26"))
    }

    @Test
    fun `isSupported with empty string returns false`() {
        assertFalse(McpProtocolVersion.isSupported(""))
    }

    // ----------------------------
    // getLatestVersion()
    // ----------------------------

    @Test
    fun `getLatestVersion returns 2025-11-25`() {
        assertEquals("2025-11-25", McpProtocolVersion.getLatestVersion())
    }

    // ----------------------------
    // getDefaultVersion()
    // ----------------------------

    @Test
    fun `getDefaultVersion returns 2025-11-25`() {
        assertEquals("2025-03-26", McpProtocolVersion.getDefaultVersion())
    }

    // ----------------------------
    // Constants
    // ----------------------------

    @Test
    fun `HEADER_NAME constant is MCP-Protocol-Version`() {
        assertEquals("MCP-Protocol-Version", McpProtocolVersion.HEADER_NAME)
    }

    @Test
    fun `VERSION_2025_11_25 constant is 2025-11-25`() {
        assertEquals("2025-11-25", McpProtocolVersion.VERSION_2025_11_25)
    }
}
