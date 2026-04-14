package jetbrains.buildServer.ai.mcp.events

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.TeamCityCloud
import jetbrains.buildServer.ai.mcp.SettingsService
import jetbrains.buildServer.ai.mcp.events.McpFusUtils.MCP_FUS_ENABLED
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.BuildServerListenerEventDispatcher
import jetbrains.buildServer.serverSide.CurrentNodeInfo
import jetbrains.buildServer.serverSide.TeamCityProperties
import jetbrains.buildServer.serverSide.executors.ExecutorServices
import jetbrains.buildServer.serverSide.impl.fus.FusRegistry
import org.jetbrains.teamcity.fus.domain.model.states.ai.McpServerStateGroup
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
open class FusStateCollectorScheduledTask(
    executorServices: ExecutorServices,
    buildServerListenerEventDispatcher: BuildServerListenerEventDispatcher,
    private val settingsService: SettingsService,
    private val fusRegistry: FusRegistry
): Runnable {

    companion object {
        private val LOGGER = Logger.getInstance(FusStateCollectorScheduledTask::class.java.name)
    }

    init {
        buildServerListenerEventDispatcher.addListener(object : BuildServerAdapter() {
            override fun serverStartup() {
                executorServices.normalExecutorService.scheduleWithFixedDelay(this@FusStateCollectorScheduledTask, 6, 60*24, TimeUnit.MINUTES)
            }
        })
    }

    private val fusEventsPresent by lazy { areFusEventClassesPresent() }

    override fun run() {
        if (!CurrentNodeInfo.isMainNode()) return
        if (!TeamCityProperties.getBooleanOrTrue(MCP_FUS_ENABLED) || !fusEventsPresent) return

        logWithProperLevel("Starting FUS states collection for MCP server feature")

        try {
            fusRegistry.logEvent(
                McpServerStateGroup.McpServerState(
                    isMcpServerEnabled = settingsService.isMcpServerEnabled()
                )
            )
        } catch (e: Throwable) {
            LOGGER.warnAndDebugDetails("Cannot collect state in McpServerStateGroup", e)
        }

        logWithProperLevel("Finished FUS states collection for MCP server feature")
    }

    protected open fun areFusEventClassesPresent(): Boolean {
        return McpFusUtils
            .areFusEventClassesPresent("org.jetbrains.teamcity.fus.domain.model.states.ai.McpServerStateGroup\$McpServerState")
    }

    private fun logWithProperLevel(message: String) {
        if (TeamCityCloud.isCloud()) {
            LOGGER.info(message)
        } else {
            LOGGER.debug(message)
        }
    }
}