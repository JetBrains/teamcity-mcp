package jetbrains.buildServer.ai.mcp.framework.e2e

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.Properties

/**
 * Toggles the `teamcity.ai.mcp.braveMode.enabled` internal property at runtime
 * for e2e tests that need to flip between safe and brave modes.
 *
 * Writes `$TC_DATA_PATH/config/internal.properties`. TeamCity watches this file
 * and reloads properties within ~10s of modification; callers should poll the
 * MCP tool list for the resulting change rather than sleeping blindly.
 *
 * Requires the `TC_DATA_PATH` system property or env var pointing to the
 * TeamCity **data** directory (the one containing `config/`) — not the
 * installation directory used by `TC_HOME`.
 */
object BraveModeControl {
    const val MCP_BRAVE_MODE_TOGGLE = "teamcity.ai.mcp.braveMode.enabled"

    fun dataPath(): String? =
        System.getProperty("TC_DATA_PATH") ?: System.getenv("TC_DATA_PATH")

    fun assumeAvailable() {
        val path = dataPath()
        assumeTrue(
            path != null && File(path, "config").isDirectory,
            "TC_DATA_PATH not set or does not point to a TC data directory — skipping brave-mode test"
        )
    }

    /** Returns the current value of the brave-mode property, or null if unset. */
    fun readBraveMode(): String? {
        val file = internalPropertiesFile()
        if (!file.exists()) return null
        val props = Properties()
        file.inputStream().use { props.load(it) }
        return props.getProperty(MCP_BRAVE_MODE_TOGGLE)
    }

    fun setBraveMode(enabled: Boolean) {
        val file = internalPropertiesFile()
        val props = Properties()
        if (file.exists()) file.inputStream().use { props.load(it) }
        props.setProperty(MCP_BRAVE_MODE_TOGGLE, enabled.toString())
        writeProps(file, props)
    }

    /**
     * Restore the brave-mode property to a previous value captured via [readBraveMode].
     * Passing `null` removes the property entirely.
     */
    fun restoreBraveMode(previous: String?) {
        val file = internalPropertiesFile()
        if (previous == null) {
            if (!file.exists()) return
            val props = Properties()
            file.inputStream().use { props.load(it) }
            if (props.remove(MCP_BRAVE_MODE_TOGGLE) == null) return
            writeProps(file, props)
        } else {
            val props = Properties()
            if (file.exists()) file.inputStream().use { props.load(it) }
            if (props.getProperty(MCP_BRAVE_MODE_TOGGLE) == previous) return
            props.setProperty(MCP_BRAVE_MODE_TOGGLE, previous)
            writeProps(file, props)
        }
    }

    private fun writeProps(file: File, props: Properties) {
        file.parentFile.mkdirs()
        file.outputStream().use { props.store(it, "updated by e2e test") }
    }

    private fun internalPropertiesFile(): File {
        val path = dataPath() ?: error("TC_DATA_PATH is not set")
        return File(path, "config/internal.properties")
    }
}
