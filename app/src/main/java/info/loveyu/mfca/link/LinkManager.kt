package info.loveyu.mfca.link

import android.content.Context
import android.net.ConnectivityManager
import info.loveyu.mfca.config.models.AppConfig
import info.loveyu.mfca.config.models.LinkConfig
import info.loveyu.mfca.config.models.LinkType
import info.loveyu.mfca.input.InputManager
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.NetworkChecker
import kotlinx.coroutines.flow.StateFlow

/**
 * 链接池管理器 - Android Service 常驻架构
 *
 * 特性:
 * - 网络状态监听，自动重连
 * - 定时检查链路健康状态
 * - 支持网络条件筛选
 */
object LinkManager {

    private val links = mutableMapOf<String, Link>()
    private val configs = mutableMapOf<String, LinkConfig>()
    private var applicationContext: Context? = null

    @Volatile
    private var lastReconnectTime = 0L

    // Whether initialization is complete (links ready for connection)
    @Volatile
    private var initialized = false

    // Notification state for link errors
    private val notifiedErrorLinks = mutableSetOf<String>()

    private val networkMonitor = LinkNetworkMonitor(
        onResetAllFailureCounts = { resetAllFailureCounts() },
        onDisconnectAll = { disconnectAll() },
        onCheckAllLinkConditions = { checkAllLinkConditions() },
        onTriggerMqttKeepAliveProbe = { triggerImmediateMqttKeepAliveProbe(it) },
        isInitialized = { initialized },
    )

    val networkStateVersion: StateFlow<Int> get() = networkMonitor.networkStateVersion
    private val linkStateListeners = mutableSetOf<(String, Boolean) -> Unit>()

    enum class NetworkType {
        WIFI, MOBILE, ETHERNET, UNKNOWN
    }

    fun setContext(context: Context) {
        applicationContext = context.applicationContext
    }

    fun getContext(): Context? = applicationContext

    fun initialize(config: AppConfig) {
        initialized = false
        clear()
        val ctx = applicationContext ?: return
        LogManager.logDebug("LINK", "Initializing LinkManager with ${config.links.size} links")
        config.links.forEach { linkConfig ->
            // HTTP links are managed by SharedHttpInput in InputManager, skip them here
            val type = LinkType.fromDsn(linkConfig.dsn)
            if (type == LinkType.http) {
                configs[linkConfig.id] = linkConfig
                LogManager.logDebug("LINK", "Skipped HTTP link: ${linkConfig.id} (managed by SharedHttpInput)")
                return@forEach
            }
            configs[linkConfig.id] = linkConfig
            val link = createLink(linkConfig)
            val ctx = applicationContext!!
            link.maxFailureCallback = { LinkNotificationHelper.showLinkError(ctx, link.id, notifiedErrorLinks) }
            link.recoveredCallback = { LinkNotificationHelper.showLinkRecovered(ctx, link.id, notifiedErrorLinks) }
            links[linkConfig.id] = link
            LogManager.logDebug("LINK", "Registered link: ${linkConfig.id} (${LinkType.fromDsn(linkConfig.dsn)})")
        }

        // Start network monitoring (updateNetworkType won't trigger connections until initialized=true)
        startNetworkMonitoring()
        initialized = true
        LogManager.logDebug("LINK", "LinkManager initialized: ${links.size} active links")
    }

    /**
     * 启动网络状态监听
     */
    private fun startNetworkMonitoring() {
        val ctx = applicationContext ?: return
        networkMonitor.startMonitoring(ctx)
    }

    private fun updateNetworkType() {
        val ctx = applicationContext ?: return
        networkMonitor.updateNetworkType(ctx)
    }

    /**
     * 统一 Ticker 调用：执行链路健康检查 + MQTT 心跳日志
     */
    fun onTick(): Long? {
        applicationContext ?: return null
        if (!networkMonitor.isNetworkAvailable) return null

        checkAllLinkConditions()

        val now = System.currentTimeMillis()
        var nextDelayMs: Long? = null

        // MQTT 心跳日志
        links.values.forEach { link ->
            if (link is MqttLink) {
                link.onTick(now)?.let { delayMs ->
                    nextDelayMs = nextDelayMs?.let { minOf(it, delayMs) } ?: delayMs
                }
                link.collectHeartbeatStatus()?.let { LogManager.logDebug("MQTT", it) }
            }
        }
        return nextDelayMs
    }

    /**
     * 统一 Ticker 调用：每 ~10min 重置失败计数
     */
    fun onFailureResetTick() {
        if (!networkMonitor.isNetworkAvailable) return
        LogManager.logDebug("LINK", "Periodic failure count reset")
        resetAllFailureCounts()
        checkAllLinkConditions()
    }

    /**
     * 检查链路健康状态，自动重连断开的链路
     */
    private fun checkLinkHealth() {
        val ctx = applicationContext ?: return
        if (!networkMonitor.isNetworkAvailable) return

        checkAllLinkConditions()
    }

    private fun checkAllLinkConditions() {
        val ctx = applicationContext ?: return
        if (!networkMonitor.isNetworkAvailable) return

        links.values.forEach { link ->
            val config = configs[link.id] ?: return@forEach

            // Check network conditions (when/deny)
            if (!info.loveyu.mfca.util.NetworkChecker.shouldEnable(ctx, config.whenCondition, config.deny)) {
                if (link.isConnected()) {
                    LogManager.logDebug("LINK", "Disconnecting ${link.id}: network conditions not met")
                    link.disconnect()
                }
                return@forEach
            }

            // Skip if auto-reconnect is disabled
            if (!link.shouldAutoReconnect()) return@forEach

            // Async links (e.g. WebSocket) may still be handshaking.
            // Skip reconnect while a connect attempt is already in progress.
            if (link.isConnecting()) return@forEach

            // Try to reconnect if disconnected
            if (!link.isConnected()) {
                try {
                    val attempted = link.connect()
                    if (attempted) {
                        LogManager.logDebug("LINK", "Reconnecting ${link.id}...")
                    }
                } catch (e: Exception) {
                    LogManager.logWarn("LINK", "Reconnect failed for ${link.id}: ${e.message}")
                }
            }
        }
    }

    /**
     * 异步重连所有链路（带防抖）
     */
    private fun reconnectAllAsync() {
        val now = System.currentTimeMillis()
        if (now - lastReconnectTime < 5_000L) {
            LogManager.logDebug("LINK", "Reconnect debounced, skipping (interval < 5s)")
            return
        }
        lastReconnectTime = now
        reconnectAll()
    }

    fun getLink(id: String): Link? = links[id]

    fun getLinkConfig(id: String): LinkConfig? = configs[id]

    /**
     * 检查指定链接的网络条件（whenCondition/deny）是否满足
     */
    fun shouldEnableLink(linkId: String): Boolean {
        val ctx = applicationContext ?: return false
        val config = configs[linkId] ?: return false
        return NetworkChecker.shouldEnable(ctx, config.whenCondition, config.deny)
    }

    fun connectAll() {
        val ctx = applicationContext ?: return
        links.values.forEach { link ->
            val config = configs[link.id] ?: return@forEach

            // Check if link should be enabled based on when/deny conditions
            if (!info.loveyu.mfca.util.NetworkChecker.shouldEnable(ctx, config.whenCondition, config.deny)) {
                LogManager.logDebug("LINK", "Skipping ${link.id}: network conditions not met")
                return@forEach
            }

            try {
                if (link.isConnecting()) {
                    return@forEach
                }
                if (!link.isConnected()) {
                    link.connect()
                }
            } catch (e: Exception) {
                LogManager.logError("LINK", "Failed to connect ${link.id}: ${e.message}")
            }
        }
    }

    /**
     * 重连所有链路
     */
    fun reconnectAll() {
        val ctx = applicationContext ?: return
        if (!networkMonitor.isNetworkAvailable) {
            LogManager.logDebug("LINK", "Network unavailable, skipping reconnect")
            return
        }

        links.values.forEach { link ->
            val config = configs[link.id] ?: return@forEach

            // Skip if auto-reconnect is disabled
            if (!link.shouldAutoReconnect()) return@forEach

            // Check network conditions (when/deny)
            if (!info.loveyu.mfca.util.NetworkChecker.shouldEnable(ctx, config.whenCondition, config.deny)) {
                LogManager.logDebug("LINK", "Skipping ${link.id}: network conditions not met")
                return@forEach
            }

            try {
                if (link.isConnecting()) {
                    return@forEach
                }
                if (!link.isConnected()) {
                    if (link.connect()) {
                        LogManager.logDebug("LINK", "Reconnected ${link.id}")
                    }
                }
            } catch (e: Exception) {
                LogManager.logError("LINK", "Failed to reconnect ${link.id}: ${e.message}")
            }
        }
    }

    fun disconnectAll() {
        links.values.forEach { link ->
            try {
                link.disconnect()
            } catch (e: Exception) {
                LogManager.logError("LINK", "Error disconnecting ${link.id}: ${e.message}")
            }
        }
    }

    /**
     * 重置所有链接的连续失败计数。
     * 网络变更时调用，确保网络恢复后链接能立即重试连接，
     * 不会因之前的失败计数而跳过重连。
     */
    private fun resetAllFailureCounts() {
        links.values.forEach { link ->
            link.resetFailureCount()
        }
        LogManager.logDebug("LINK", "Reset all link failure counts on network change")
    }

    fun clear() {
        initialized = false
        unregisterNetworkCallback()
        disconnectAll()
        val ctx = applicationContext
        if (ctx != null) LinkNotificationHelper.cancelAll(ctx, notifiedErrorLinks)
        links.clear()
        configs.clear()
    }

    private fun unregisterNetworkCallback() {
        val ctx = applicationContext ?: return
        networkMonitor.unregisterCallback(ctx)
    }

    // ---- Link error notification helpers ----

    private fun createLink(config: LinkConfig): Link {
        val ctx = applicationContext ?: throw IllegalStateException("Application context not set")
        // Derive type from DSN protocol or URL
        val type = LinkType.fromDsn(config.dsn)
        return when (type) {
            LinkType.mqtt -> MqttLink(config, ctx)
            LinkType.websocket -> WebSocketLink(config).also { it.setContext(ctx) }
            LinkType.tcp -> TcpLink(config, ctx)
            LinkType.http -> throw IllegalArgumentException("HTTP links are not managed by LinkManager, use SharedHttpInput instead")
        }
    }

    fun getAllLinks(): Map<String, Link> = links.toMap()

    /**
     * 获取所有 HTTP 类型的 link 配置（由 SharedHttpInput 管理，不创建 Link 对象）
     */
    fun getHttpLinkConfigs(): Map<String, LinkConfig> {
        return configs.filter { (id, _) ->
            val type = LinkType.fromDsn(configs[id]?.dsn)
            type == LinkType.http
        }
    }

    fun getLinkState(id: String): LinkState {
        val link = links[id] ?: return LinkState.disconnected
        return if (link.isConnected()) LinkState.connected else LinkState.disconnected
    }

    fun getNetworkInfo(): String {
        val ctx = applicationContext ?: return "No context"
        return NetworkChecker.getNetworkInfo(ctx)
    }

    fun isNetworkAvailable(): Boolean = networkMonitor.isNetworkAvailable

    fun refreshNetworkState() {
        val ctx = applicationContext ?: return
        NetworkChecker.invalidateCache()
        NetworkChecker.refreshCache(ctx)
        networkMonitor.isNetworkAvailable = (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork != null
        updateNetworkType()

        if (!initialized) return

        if (!networkMonitor.isNetworkAvailable) {
            disconnectAll()
            InputManager.stopAllLinkBased()
            InputManager.stopAllUdp2Raw()
            return
        }

        checkAllLinkConditions()
        InputManager.checkAllInputConditions()
    }

    fun getCurrentNetworkType(): NetworkType = networkMonitor.currentNetworkType

    /**
     * 通知 UI 链路连接状态已变化（connected/disconnected）。
     * 用于触发组件状态面板及时刷新，不必等待网络回调或下一次 tick。
     */
    fun notifyLinkStateChanged(linkId: String, connected: Boolean) {
        LogManager.logDebug("LINK", "Link state changed: $linkId connected=$connected")
        networkMonitor.signalStateChange()
        val listeners = synchronized(linkStateListeners) { linkStateListeners.toList() }
        listeners.forEach { listener ->
            try {
                listener(linkId, connected)
            } catch (e: Exception) {
                LogManager.logWarn("LINK", "Link state listener failed for $linkId: ${e.message}")
            }
        }
    }

    fun addLinkStateListener(listener: (String, Boolean) -> Unit) {
        synchronized(linkStateListeners) {
            linkStateListeners.add(listener)
        }
    }

    fun removeLinkStateListener(listener: (String, Boolean) -> Unit) {
        synchronized(linkStateListeners) {
            linkStateListeners.remove(listener)
        }
    }

    private fun triggerImmediateMqttKeepAliveProbe(reason: String) {
        links.values.forEach { link ->
            if (link is MqttLink) {
                link.triggerImmediateKeepAliveProbe(reason)
            }
        }
    }

    /**
     * 获取已连接的链路数量
     */
    fun getConnectedCount(): Int = links.values.count { it.isConnected() }

    /**
     * 获取链路总数
     */
    fun getTotalCount(): Int = links.size
}
