package jetbrains.buildServer.ai.mcp

/**
 * MCP Protocol version validation and management.
 * Handles validation of the MCP-Protocol-Version header according to the MCP specification.
 */
object McpProtocolVersion {

    /**
     * Latest supported protocol version (Streamable HTTP transport)
     */
    const val VERSION_2025_11_25 = "2025-11-25"

    /**
     * Older Streamable HTTP protocol version (used by Gemini CLI)
     */
    const val VERSION_2025_06_18 = "2025-06-18"

    /**
     * HTTP header name for protocol version
     */
    const val HEADER_NAME = "MCP-Protocol-Version"

    /**
     * Default protocol version when header is not provided (for backward compatibility)
     */
    private const val DEFAULT_VERSION = "2025-03-26"

    /**
     * All supported protocol versions
     */
    private val SUPPORTED_VERSIONS = setOf(VERSION_2025_11_25, VERSION_2025_06_18)

    /**
     * Validation result for protocol version
     */
    sealed class ValidationResult {
        /**
         * Version is valid and supported
         * @param version the validated protocol version
         */
        data class Valid(val version: String) : ValidationResult()

        /**
         * Version is missing (no header provided) - defaults to VERSION_2025_11_25 for backward compatibility
         */
        data class Missing(val defaultVersion: String = DEFAULT_VERSION) : ValidationResult()

        /**
         * Version is invalid or unsupported
         * @param providedVersion the version that was provided
         * @param reason explanation of why it's invalid
         */
        data class Invalid(val providedVersion: String, val reason: String) : ValidationResult()
    }

    /**
     * Validate the protocol version from request header
     * @param version the protocol version string from the header (null if header not present)
     * @return validation result
     */
    fun validate(version: String?): ValidationResult {
        // If no version header provided, default to 2025-11-25 for backward compatibility
        if (version == null || version.isBlank()) {
            return ValidationResult.Missing()
        }

        // Check if version is supported
        return if (SUPPORTED_VERSIONS.contains(version)) {
            ValidationResult.Valid(version)
        } else {
            ValidationResult.Invalid(
                providedVersion = version,
                reason = "Protocol version '$version' is not supported. Supported versions: ${getSupportedVersions().joinToString(", ")}"
            )
        }
    }

    /**
     * Get all supported protocol versions
     * @return list of supported versions in descending order (newest first)
     */
    fun getSupportedVersions(): List<String> {
        return listOf(VERSION_2025_11_25, VERSION_2025_06_18)
    }

    /**
     * Check if a version is supported
     * @param version the protocol version to check
     * @return true if supported, false otherwise
     */
    fun isSupported(version: String): Boolean {
        return SUPPORTED_VERSIONS.contains(version)
    }

    /**
     * Get the latest supported protocol version
     * @return the newest protocol version
     */
    fun getLatestVersion(): String {
        return VERSION_2025_11_25
    }

    /**
     * Get the default protocol version (used when header is missing)
     * @return the default protocol version
     */
    fun getDefaultVersion(): String {
        return DEFAULT_VERSION
    }
}
