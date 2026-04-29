package info.loveyu.mfca.input

import android.content.Context
import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.config.HttpInputConfig
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.NetworkChecker

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
    private val linkInputConfigs = mutableListOf<info.loveyu.mfca.config.LinkInputConfig>()
    private var globalMessageListener: ((InputMessage) -> Unit)? = null
    private var applicationContext: Context? = null

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
    ) {
        fun uniqueKey(): String = if (linkId.isNullOrBlank()) name else "$name@$linkId"
    }

    fun setContext(context: Context) {
        applicationContext = context.applicationContext
    }

    fun initialize(config: AppConfig, messageHandler: (InputMessage) -> Unit) {
        clear()
        globalMessageListener = messageHandler
        linkInputConfigs.addAll(config.inputs.link)
        LogManager.logDebug("INPUT", "Initializing InputManager with ${config.inputs.http.size} HTTP inputs, ${config.inputs.link.size} link inputs")

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
            LogManager.logDebug("INPUT", "Registered HTTP input: ${httpConfig.name} dsn=${httpConfig.dsn}")
        }

        // Shared HTTP inputs (with link_id → SharedHttpInput + HttpVirtualInputs)
        sharedGroups.forEach { (linkId, httpConfigs) ->
            val linkConfig = config.links.find { it.id == linkId }
            if (linkConfig == null) {
                LogManager.logWarn("INPUT", "Shared HTTP group skipped: link_id '$linkId' not found in links")
                httpConfigs.forEach { httpConfig ->
                    LogManager.logWarn("INPUT", "  -> skipping HTTP input: ${httpConfig.name}")
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
                LogManager.logDebug("INPUT", "Registered shared HTTP input: ${httpConfig.name} (link: $linkId)")
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
            LogManager.logDebug("INPUT", "Registered shared HTTP server for link: $linkId with ${httpConfigs.size} virtual inputs")
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
                LogManager.logDebug("INPUT", "Registered ${linkConfig.role} input: ${linkConfig.name} (link: $linkId)")
            }
        }

        LogManager.logDebug("INPUT", "InputManager initialized: ${entries.size} inputs registered")
    }

    /**
     * 统一 Ticker 调用：执行输入源健康检查
     */
    fun onTick() {
        val ctx = applicationContext ?: return
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
                            LogManager.logDebug("INPUT", "Stopping shared HTTP server ${config.name}: network conditions not met")
                            input.stop()
                        }
                    } else if (!input.isRunning() && !input.hasFatalError()) {
                        LogManager.logDebug("INPUT", "Restarting shared HTTP server: ${config.name}")
                        try {
                            input.start()
                        } catch (e: Exception) {
                            LogManager.logError("INPUT", "Restart failed for shared HTTP server ${config.name}: ${e.message}")
                        }
                    }
                }
                return@forEach
            }

            // Check network conditions (when/deny)
            if (!NetworkChecker.shouldEnable(ctx, config.whenCondition, config.deny)) {
                if (input.isRunning()) {
                    LogManager.logDebug("INPUT", "Stopping ${config.name}: network conditions not met")
                    input.stop()
                }
                return@forEach
            }

            // For link-based inputs, check if the associated link is connected
            if (config.isLinkBased && config.linkId != null) {
                // Check link's network conditions (when/deny)
                if (!LinkManager.shouldEnableLink(config.linkId)) {
                    if (input.isRunning()) {
                        LogManager.logDebug("INPUT", "Stopping ${config.name}: link ${config.linkId} network conditions not met")
                        input.stop()
                    }
                    return@forEach
                }
                val link = LinkManager.getLink(config.linkId)
                if (link == null || !link.isConnected()) {
                    // Skip stopping if link is still connecting (startup phase)
                    if (link?.isConnecting() == true) return@forEach
                    if (input.isRunning()) {
                        LogManager.logDebug("INPUT", "Stopping ${config.name}: link ${config.linkId} not connected")
                        input.stop()
                    }
                    return@forEach
                }
            }

            // Try to restart if not running (skip inputs with permanent errors)
            if (!input.isRunning()) {
                // Skip inputs with permanent errors (e.g. DSN parse failure)
                if (input.hasFatalError()) {
                    return@forEach
                }
                LogManager.logDebug("INPUT", "Restarting ${config.name} (link: ${config.linkId})...")
                try {
                    input.start()
                } catch (e: Exception) {
                    LogManager.logError("INPUT", "Restart failed for ${config.name}: ${e.message}")
                }
            }
        }
    }

    fun startAll() {
        val ctx = applicationContext
        LogManager.logDebug("INPUT", "Starting all inputs (${entries.size} entries)")
        entries.forEach { entry ->
            try {
                // Check input's own network conditions (when/deny)
                if (ctx != null && !NetworkChecker.shouldEnable(ctx, entry.config.whenCondition, entry.config.deny)) {
                    LogManager.logDebug("INPUT", "Skipping ${entry.config.name}: network conditions not met")
                    return@forEach
                }
                // For link-based inputs, also check the link's conditions
                if (ctx != null && entry.config.isLinkBased && entry.config.linkId != null) {
                    val linkConfig = LinkManager.getLinkConfig(entry.config.linkId)
                    if (linkConfig != null && !NetworkChecker.shouldEnable(ctx, linkConfig.whenCondition, linkConfig.deny)) {
                        LogManager.logDebug("INPUT", "Skipping ${entry.config.name}: link ${entry.config.linkId} network conditions not met")
                        return@forEach
                    }
                }
                if (!entry.input.isRunning()) {
                    entry.input.start()
                }
            } catch (e: Exception) {
                LogManager.logError("INPUT", "Failed to start ${entry.config.name}: ${e.message}")
            }
        }
    }

    fun stopAll() {
        entries.forEach { entry ->
            try {
                entry.input.stop()
            } catch (e: Exception) {
                LogManager.logError("INPUT", "Error stopping ${entry.config.name}: ${e.message}")
            }
        }
    }

    /**
     * 仅停止 link-based 输入源（MQTT/WS/TCP 订阅类）。
     * 网络真正断开时调用，HTTP Server 类输入不受影响。
     */
    fun stopAllLinkBased() {
        entries.forEach { entry ->
            if (!entry.config.isLinkBased) return@forEach
            try {
                if (entry.input.isRunning()) {
                    LogManager.logDebug("INPUT", "Stopping link-based input on network loss: ${entry.config.name}")
                    entry.input.stop()
                }
            } catch (e: Exception) {
                LogManager.logError("INPUT", "Error stopping ${entry.config.name}: ${e.message}")
            }
        }
    }

    fun clear() {
        stopAll()
        entries.clear()
        linkInputConfigs.clear()
        globalMessageListener = null
    }

    fun getInput(name: String, linkId: String? = null): InputSource? =
        entries.find {
            it.config.name == name &&
                !it.config.isSharedServer &&
                (linkId == null || it.config.linkId == linkId)
        }?.input

    fun getAllInputs(): Map<String, InputSource> =
        entries
            .filter { !it.config.isSharedServer }
            .associate { it.config.uniqueKey() to it.input }

    fun getInputState(name: String, linkId: String? = null): Boolean =
        entries.any {
            it.config.name == name &&
                !it.config.isSharedServer &&
                (linkId == null || it.config.linkId == linkId) &&
                it.input.isRunning()
        }

    /**
     * 查找指定名称的 link input 配置。
     * 供 GotifyIconEnricher 判断参数是 inputId 还是 linkId 时使用。
     */
    fun getLinkInputConfigByName(name: String): info.loveyu.mfca.config.LinkInputConfig? =
        linkInputConfigs.firstOrNull { it.name == name }

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
