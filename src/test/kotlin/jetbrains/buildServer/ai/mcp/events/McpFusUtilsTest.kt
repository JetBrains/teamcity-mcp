package jetbrains.buildServer.ai.mcp.events

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpFusUtilsTest {

    @Test
    fun `FUS event class name resolves to a real class`() {
        assertTrue(
            McpFusUtils.areFusEventClassesPresent(
                "org.jetbrains.teamcity.fus.domain.model.events.ai.McpServerEventsGroup\$SessionStartedEvent"
            ),
            "SessionStartedEvent must be loadable — check that the class name uses '$' for nested classes, not '.'"
        )
    }

    @Test
    fun `FUS state class name resolves to a real class`() {
        assertTrue(
            McpFusUtils.areFusEventClassesPresent(
                "org.jetbrains.teamcity.fus.domain.model.states.ai.McpServerStateGroup\$McpServerState"
            ),
            "McpServerState must be loadable — check that the class name uses '$' for nested classes, not '.'"
        )
    }
}
