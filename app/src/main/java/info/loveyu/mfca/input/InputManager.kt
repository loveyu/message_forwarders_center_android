package info.loveyu.mfca.input

import android.content.Context
import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.config.HttpInputConfig
import info.loveyu.mfca.config.LinkInputConfig
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.NetworkChecker
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 输入源管理器 - Service 常驻架构
 *
 * 特性:
 * - 定时检查输入源健康状态
 * - 自动重启断开的输入源
 * - 支持链接状态联动
 * - 支持单个输入绑定多个 link_id
 */
object InputManager {

    private data class InputEntry(
        val input: InputSource,
        val config: InputSourceConfig
    )

    private val entries = mutableListOf<InputEntry>()
    private var globalMessageListener: ((InputMessage) -> Unit)? = null
    private var applicationContext: Context? = null

    // Service-resident executor
    private val inputScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var healthCheckFuture: ScheduledFuture<*>? = null

    /**
     * 输入源配置信息
     */
    private data class InputSourceConfig(
        val name: String,
        val isLinkBased: Boolean = false,
        val linkId: String? = null,
        val isSharedServer: Boolean = false,
        val whenCondition: String? = null,
        val deny: String? = null
    )

    fun setContext(context: Context) {
        applicationContext = context.applicationContext
    }

    fun initialize(config: AppConfig, messageHandler: (InputMessage) -> Unit) {
        clear()
        globalMessageListener = messageHandler

        // HTTP inputs - group by linkId for shared mode
        val standaloneInputs = mutableListOf<HttpInputConfig>()
        val sharedGroups = mutableMapOf<String, MutableList<HttpInputConfig>>()

        config.inputs.http.forEach { httpConfig ->
            if (httpConfig.linkId != null) {
                sharedGroups.getOrPut(httpConfig.linkId) { mutableListOf() }.add(httpConfig)
            } else {
                standaloneInputs.add(httpConfig)
            }
        }

        // Standalone HTTP inputs (no link_id → independent server)
        standaloneInputs.forEach { httpConfig ->
            val input = HttpInput(httpConfig)
            entries.add(InputEntry(
                input = input,
                config = InputSourceConfig(
                    name = httpConfig.name,
                    isLinkBased = false,
                    linkId = null,
                    whenCondition = httpConfig.whenCondition,
                    deny = httpConfig.deny
                )
            ))
            input.setOnMessageListener { msg -> messageHandler(msg) }
            LogManager.appendLog("INPUT", "Registered HTTP input: ${httpConfig.name} dsn=${httpConfig.dsn}")
        }

        // Shared HTTP inputs (with link_id → SharedHttpInput + HttpVirtualInputs)
        sharedGroups.forEach { (linkId, httpConfigs) ->
            val linkConfig = config.links.find { it.id == linkId }
            if (linkConfig == null) {
                LogManager.appendLog("INPUT", "Shared HTTP group skipped: link_id '$linkId' not found in links")
                httpConfigs.forEach { httpConfig ->
                    LogManager.appendLog("INPUT", "  → skipping HTTP input: ${httpConfig.name}")
                }
                return@forEach
            }

            val sharedInput = SharedHttpInput(linkConfig)

            httpConfigs.forEach { httpConfig ->
                val virtualInput = HttpVirtualInput(httpConfig)
                sharedInput.addVirtualInput(virtualInput)

                entries.add(InputEntry(
                    input = virtualInput,
                    config = InputSourceConfig(
                        name = httpConfig.name,
                        isLinkBased = false,
                        linkId = linkId,
                        whenCondition = httpConfig.whenCondition,
                        deny = httpConfig.deny
                    )
                ))
                virtualInput.setOnMessageListener { msg -> messageHandler(msg) }
                LogManager.appendLog("INPUT", "Registered shared HTTP input: ${httpConfig.name} (link: $linkId)")
            }

            // Register SharedHttpInput as a special entry for lifecycle management
            entries.add(InputEntry(
                input = sharedInput,
                config = InputSourceConfig(
                    name = sharedInput.inputName,
                    isLinkBased = false,
                    linkId = linkId,
                    isSharedServer = true,
                    whenCondition = linkConfig.whenCondition,
                    deny = linkConfig.deny
                )
            ))
            LogManager.appendLog("INPUT", "Registered shared HTTP server for link: $linkId with ${httpConfigs.size} virtual inputs")
        }

        // Link-based inputs (MQTT, WebSocket, TCP)
        // 支持 link_id 为数组，展开为多个 InputSource 实例
        config.inputs.link.forEach { linkConfig ->
            val ids = if (linkConfig.linkIds.isNotEmpty()) linkConfig.linkIds else listOf(linkConfig.linkId)
            ids.forEach { linkId ->
                val perLinkConfig = linkConfig.copy(linkId = linkId)
                val input = createLinkInput(perLinkConfig, config.links)
                entries.add(InputEntry(
                    input = input,
                    config = InputSourceConfig(
                        name = linkConfig.name,
                        isLinkBased = true,
                        linkId = linkId,
                        whenCondition = linkConfig.whenCondition,
                        deny = linkConfig.deny
                    )
                ))
                input.setOnMessageListener { msg -> messageHandler(msg) }
                LogManager.appendLog("INPUT", "Registered ${linkConfig.role} input: ${linkConfig.name} (link: $linkId)")
            }
        }

        // Start health check
        startHealthCheck()
    }

    /**
     * 启动输入源健康检查 (每30秒)
     */
    fun startHealthCheck() {
        healthCheckFuture?.cancel(false)
        healthCheckFuture = inputScheduler.scheduleAtFixedRate({
            checkInputHealth()
        }, 30, 30, TimeUnit.SECONDS)
    }

    /**
     * 停止输入源健康检查
     */
    fun stopHealthCheck() {
        healthCheckFuture?.cancel(false)
        healthCheckFuture = null
    }

    /**
     * 检查输入源健康状态，自动重启断开的输入源
     */
    private fun checkInputHealth() {
        if (!LinkManager.isNetworkAvailable()) return

        checkAllInputConditions()
    }

    /**
     * 检查所有输入源的网络条件，不符合的停止，符合但停止的重新启动
     */
    fun checkAllInputConditions() {
        val ctx = applicationContext ?: return
        if (!LinkManager.isNetworkAvailable()) return

        entries.forEach { entry ->
            val input = entry.input
            val config = entry.config

            // SharedHttpInput server entries manage a shared NanoHTTPD lifecycle
            // Virtual inputs (HttpVirtualInput) always report running, skip health check
            if (config.isSharedServer || input is HttpVirtualInput) {
                if (config.isSharedServer) {
                    // Check network conditions from link's when/deny
                    if (!NetworkChecker.shouldEnable(ctx, config.whenCondition, config.deny)) {
                        if (input.isRunning()) {
                            LogManager.appendLog("INPUT", "Stopping shared HTTP server ${config.name}: network conditions not met")
                            input.stop()
                        }
                    } else if (!input.isRunning() && input.getError() == null) {
                        LogManager.appendLog("INPUT", "Restarting shared HTTP server: ${config.name}")
                        try {
                            input.start()
                        } catch (e: Exception) {
                            LogManager.appendLog("INPUT", "Restart failed for shared HTTP server: ${e.message}")
                        }
                    }
                }
                return@forEach
            }

            // Check network conditions (when/deny)
            if (!NetworkChecker.shouldEnable(ctx, config.whenCondition, config.deny)) {
                if (input.isRunning()) {
                    LogManager.appendLog("INPUT", "Stopping ${config.name}: network conditions not met")
                    input.stop()
                }
                return@forEach
            }

            // For link-based inputs, check if the associated link is connected
            if (config.isLinkBased && config.linkId != null) {
                val link = LinkManager.getLink(config.linkId)
                if (link == null || !link.isConnected()) {
                    if (input.isRunning()) {
                        LogManager.appendLog("INPUT", "Stopping ${config.name}: link ${config.linkId} not connected")
                        input.stop()
                    }
                    return@forEach
                }
            }

            // Try to restart if not running (skip inputs with permanent errors)
            if (!input.isRunning()) {
                // Skip inputs with permanent errors (e.g. port conflict, DSN parse failure)
                if (input.getError() != null) {
                    return@forEach
                }
                LogManager.appendLog("INPUT", "Restarting ${config.name} (link: ${config.linkId})...")
                try {
                    input.start()
                } catch (e: Exception) {
                    LogManager.appendLog("INPUT", "Restart failed for ${config.name}: ${e.message}")
                }
            }
        }
    }

    fun startAll() {
        entries.forEach { entry ->
            try {
                if (!entry.input.isRunning()) {
                    entry.input.start()
                }
            } catch (e: Exception) {
                LogManager.appendLog("INPUT", "Failed to start ${entry.config.name}: ${e.message}")
            }
        }
    }

    fun stopAll() {
        entries.forEach { entry ->
            try {
                entry.input.stop()
            } catch (e: Exception) {
                LogManager.appendLog("INPUT", "Error stopping ${entry.config.name}: ${e.message}")
            }
        }
    }

    fun clear() {
        stopHealthCheck()
        stopAll()
        entries.clear()
        globalMessageListener = null
    }

    fun getInput(name: String): InputSource? =
        entries.find { it.config.name == name && !it.config.isSharedServer }?.input

    fun getAllInputs(): Map<String, InputSource> =
        entries.filter { !it.config.isSharedServer }.associateBy({ it.input.inputName }, { it.input })

    fun getInputState(name: String): Boolean =
        entries.any { it.config.name == name && !it.config.isSharedServer && it.input.isRunning() }

    /**
     * 获取指定 linkId 对应的 SharedHttpInput 的运行状态
     */
    fun getSharedHttpInputState(linkId: String): Boolean {
        return entries.any {
            it.config.isSharedServer && it.config.linkId == linkId && it.input.isRunning()
        }
    }

    /**
     * 获取指定 linkId 对应的 SharedHttpInput 的错误信息
     */
    fun getSharedHttpInputError(linkId: String): String? {
        return entries.find {
            it.config.isSharedServer && it.config.linkId == linkId
        }?.input?.getError()
    }

    private fun createLinkInput(config: info.loveyu.mfca.config.LinkInputConfig, links: List<info.loveyu.mfca.config.LinkConfig>): InputSource {
        val linkConfig = links.find { it.id == config.linkId }
        val linkType = linkConfig?.let { info.loveyu.mfca.config.LinkType.fromDsn(it.dsn) } ?: info.loveyu.mfca.config.LinkType.mqtt
        return when (linkType) {
            info.loveyu.mfca.config.LinkType.mqtt -> MqttInput(config)
            info.loveyu.mfca.config.LinkType.websocket -> WebSocketInput(config)
            else -> TcpInput(config)
        }
    }
}
