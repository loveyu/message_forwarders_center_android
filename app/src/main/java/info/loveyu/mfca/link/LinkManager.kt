package info.loveyu.mfca.link

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import info.loveyu.mfca.MainActivity
import info.loveyu.mfca.R
import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.input.InputManager
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.NetworkChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Network state
    @Volatile
    private var isNetworkAvailable = false
    @Volatile
    private var currentNetworkType = NetworkType.UNKNOWN
    @Volatile
    private var lastReconnectTime = 0L
    @Volatile
    private var lastNetworkTypeUpdateTime = 0L

    // Whether initialization is complete (links ready for connection)
    @Volatile
    private var initialized = false

    // Notification state for link errors
    private val notifiedErrorLinks = mutableSetOf<String>()

    private const val LINK_ERROR_NOTIFICATION_BASE = 2000

    // Network state version for UI refresh
    private val _networkStateVersion = MutableStateFlow(0)
    val networkStateVersion: StateFlow<Int> = _networkStateVersion.asStateFlow()

    enum class NetworkType {
        WIFI, MOBILE, ETHERNET, UNKNOWN
    }

    fun setContext(context: Context) {
        applicationContext = context.applicationContext
    }

    fun initialize(config: AppConfig) {
        initialized = false
        clear()
        val ctx = applicationContext ?: return
        LogManager.log("LINK", "Initializing LinkManager with ${config.links.size} links")
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
            link.maxFailureCallback = { showLinkErrorNotification(link.id) }
            link.recoveredCallback = { showLinkRecoveredNotification(link.id) }
            links[linkConfig.id] = link
            LogManager.log("LINK", "Registered link: ${linkConfig.id} (${LinkType.fromDsn(linkConfig.dsn)})")
        }

        // Start network monitoring (updateNetworkType won't trigger connections until initialized=true)
        startNetworkMonitoring()
        initialized = true
        LogManager.log("LINK", "LinkManager initialized: ${links.size} active links")
    }

    /**
     * 启动网络状态监听
     */
    private fun startNetworkMonitoring() {
        val ctx = applicationContext ?: return
        val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LogManager.log("LINK", "Network available")
                isNetworkAvailable = true
                resetAllFailureCounts()
                updateNetworkType()
                // Network available, try reconnecting links
                reconnectAllAsync()
            }

            override fun onLost(network: Network) {
                LogManager.logWarn("LINK", "Network lost")
                isNetworkAvailable = false
                resetAllFailureCounts()
                updateNetworkType()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                resetAllFailureCounts()
                updateNetworkType()
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
            // Check initial state
            val activeNetwork = connectivityManager.activeNetwork
            isNetworkAvailable = activeNetwork != null
            updateNetworkType()
        } catch (e: Exception) {
            LogManager.logError("LINK", "Failed to register network callback: ${e.message}")
        }
    }

    private fun updateNetworkType() {
        val now = System.currentTimeMillis()
        if (now - lastNetworkTypeUpdateTime < 1_000L) return
        lastNetworkTypeUpdateTime = now

        val ctx = applicationContext ?: return
        val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        if (capabilities != null) {
            currentNetworkType = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                else -> NetworkType.UNKNOWN
            }
        } else {
            currentNetworkType = NetworkType.UNKNOWN
        }

        LogManager.logDebug("LINK", "Network type: $currentNetworkType")
        LogManager.logDebug("NETWORK", NetworkChecker.getDetailedNetworkInfo(ctx))

        // Notify UI to refresh component states
        _networkStateVersion.value++

        // During initialization, skip connection management (applyConfig's connectAll handles it)
        if (!initialized) return

        // Disconnect all links when network is lost
        if (network == null) {
            disconnectAll()
            InputManager.checkAllInputConditions()
            return
        }

        // Check all links conditions
        checkAllLinkConditions()
        InputManager.checkAllInputConditions()
    }

    /**
     * 统一 Ticker 调用：执行链路健康检查 + MQTT 心跳日志
     */
    fun onTick() {
        val ctx = applicationContext ?: return
        if (!isNetworkAvailable) return

        checkAllLinkConditions()

        // MQTT 心跳日志（替代每个 MqttLink 的独立 Timer）
        links.values.forEach { link ->
            if (link is MqttLink && link.isConnected()) {
                link.logHeartbeatStatus()
            }
        }
    }

    /**
     * 统一 Ticker 调用：每 ~10min 重置失败计数
     */
    fun onFailureResetTick() {
        if (!isNetworkAvailable) return
        LogManager.logDebug("LINK", "Periodic failure count reset")
        resetAllFailureCounts()
        checkAllLinkConditions()
    }

    /**
     * 检查链路健康状态，自动重连断开的链路
     */
    private fun checkLinkHealth() {
        val ctx = applicationContext ?: return
        if (!isNetworkAvailable) return

        checkAllLinkConditions()
    }

    /**
     * 检查所有链路的网络条件，不符合的断开，符合但断开的重新连接
     */
    private fun checkAllLinkConditions() {
        val ctx = applicationContext ?: return
        if (!isNetworkAvailable) return

        links.values.forEach { link ->
            val config = configs[link.id] ?: return@forEach

            // Check network conditions (when/deny)
            if (!info.loveyu.mfca.util.NetworkChecker.shouldEnable(ctx, config.whenCondition, config.deny)) {
                if (link.isConnected()) {
                    LogManager.log("LINK", "Disconnecting ${link.id}: network conditions not met")
                    link.disconnect()
                }
                return@forEach
            }

            // Skip if auto-reconnect is disabled
            if (!link.shouldAutoReconnect()) return@forEach

            // Try to reconnect if disconnected
            if (!link.isConnected()) {
                LogManager.log("LINK", "Reconnecting ${link.id}...")
                try {
                    link.connect()
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
        if (!isNetworkAvailable) {
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
                if (!link.isConnected()) {
                    if (link.connect()) {
                        LogManager.log("LINK", "Reconnected ${link.id}")
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
        cancelAllLinkErrorNotifications()
        links.clear()
        configs.clear()
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { callback ->
            try {
                val ctx = applicationContext ?: return
                val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                LogManager.logWarn("LINK", "Failed to unregister network callback: ${e.message}")
            }
            networkCallback = null
        }
    }

    // ---- Link error notification helpers ----

    private fun showLinkErrorNotification(linkId: String) {
        val ctx = applicationContext ?: return
        synchronized(notifiedErrorLinks) {
            if (linkId in notifiedErrorLinks) return
            notifiedErrorLinks.add(linkId)
        }

        val notificationId = LINK_ERROR_NOTIFICATION_BASE + Math.abs(linkId.hashCode() % 1000)
        val contentIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = android.app.Notification.Builder(ctx, ForwardService.LINK_ERROR_CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.link_error_title))
            .setContentText(linkId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify("link_error_$linkId", notificationId, notification)
        LogManager.log("LINK", "Posted error notification for $linkId")
    }

    private fun showLinkRecoveredNotification(linkId: String) {
        val ctx = applicationContext ?: return
        synchronized(notifiedErrorLinks) {
            if (linkId !in notifiedErrorLinks) return
            notifiedErrorLinks.remove(linkId)
        }

        val notificationId = LINK_ERROR_NOTIFICATION_BASE + Math.abs(linkId.hashCode() % 1000)
        val contentIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = android.app.Notification.Builder(ctx, ForwardService.LINK_ERROR_CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.link_recovered_title))
            .setContentText(linkId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .setOngoing(false)
            .setTimeoutAfter(5_000L)
            .build()

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify("link_error_$linkId", notificationId, notification)
        LogManager.log("LINK", "Posted recovered notification for $linkId")
    }

    private fun cancelAllLinkErrorNotifications() {
        val ctx = applicationContext ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        synchronized(notifiedErrorLinks) {
            notifiedErrorLinks.forEach { linkId ->
                val notificationId = LINK_ERROR_NOTIFICATION_BASE + Math.abs(linkId.hashCode() % 1000)
                nm.cancel("link_error_$linkId", notificationId)
            }
            notifiedErrorLinks.clear()
        }
    }

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

    fun isNetworkAvailable(): Boolean = isNetworkAvailable

    /**
     * 刷新网络状态（权限变更后调用）
     */
    fun refreshNetworkState() {
        lastNetworkTypeUpdateTime = 0L
        updateNetworkType()
    }

    fun getCurrentNetworkType(): NetworkType = currentNetworkType

    /**
     * 获取已连接的链路数量
     */
    fun getConnectedCount(): Int = links.values.count { it.isConnected() }

    /**
     * 获取链路总数
     */
    fun getTotalCount(): Int = links.size
}
