package info.loveyu.mfca.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.NetworkChecker
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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

    // Service-resident executors
    private val linkScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var healthCheckFuture: ScheduledFuture<*>? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Network state
    @Volatile
    private var isNetworkAvailable = false
    @Volatile
    private var currentNetworkType = NetworkType.UNKNOWN
    @Volatile
    private var lastReconnectTime = 0L

    enum class NetworkType {
        WIFI, MOBILE, ETHERNET, UNKNOWN
    }

    fun setContext(context: Context) {
        applicationContext = context.applicationContext
    }

    fun initialize(config: AppConfig) {
        clear()
        config.links.forEach { linkConfig ->
            configs[linkConfig.id] = linkConfig
            links[linkConfig.id] = createLink(linkConfig)
            LogManager.appendLog("LINK", "Registered link: ${linkConfig.id} (${linkConfig.type})")
        }

        // Start network monitoring
        startNetworkMonitoring()
    }

    /**
     * 启动网络状态监听
     */
    private fun startNetworkMonitoring() {
        val ctx = applicationContext ?: return
        val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LogManager.appendLog("LINK", "Network available")
                isNetworkAvailable = true
                updateNetworkType()
                // Network available, try reconnecting links
                reconnectAllAsync()
            }

            override fun onLost(network: Network) {
                LogManager.appendLog("LINK", "Network lost")
                isNetworkAvailable = false
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
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
            LogManager.appendLog("LINK", "Failed to register network callback: ${e.message}")
        }
    }

    private fun updateNetworkType() {
        val ctx = applicationContext ?: return
        val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return

        currentNetworkType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.UNKNOWN
        }

        LogManager.appendLog("LINK", "Network type: $currentNetworkType")
    }

    /**
     * 启动链路健康检查 (每30秒)
     */
    fun startHealthCheck() {
        healthCheckFuture?.cancel(false)
        healthCheckFuture = linkScheduler.scheduleAtFixedRate({
            checkLinkHealth()
        }, 30, 30, TimeUnit.SECONDS)
    }

    /**
     * 停止链路健康检查
     */
    fun stopHealthCheck() {
        healthCheckFuture?.cancel(false)
        healthCheckFuture = null
    }

    /**
     * 检查链路健康状态，自动重连断开的链路
     */
    private fun checkLinkHealth() {
        val ctx = applicationContext ?: return
        if (!isNetworkAvailable) return

        links.values.forEach { link ->
            val config = configs[link.id] ?: return@forEach

            // Check network conditions
            if (!NetworkChecker.shouldEnable(ctx, config.enabledWhen)) {
                if (link.isConnected()) {
                    LogManager.appendLog("LINK", "Disconnecting ${link.id}: network conditions not met")
                    link.disconnect()
                }
                return@forEach
            }

            // Try to reconnect if disconnected
            if (!link.isConnected()) {
                // Reset failure count so the link can try again this cycle
                if (link is MqttLink) {
                    link.resetFailureCount()
                }
                LogManager.appendLog("LINK", "Reconnecting ${link.id}...")
                try {
                    link.connect()
                } catch (e: Exception) {
                    LogManager.appendLog("LINK", "Reconnect failed for ${link.id}: ${e.message}")
                }
            }
        }
    }

    /**
     * 异步重连所有链路（带防抖）
     */
    private fun reconnectAllAsync() {
        linkScheduler.execute {
            val now = System.currentTimeMillis()
            if (now - lastReconnectTime < 5_000L) return@execute
            lastReconnectTime = now
            reconnectAll()
        }
    }

    fun getLink(id: String): Link? = links[id]

    fun getLinkConfig(id: String): LinkConfig? = configs[id]

    fun connectAll() {
        val ctx = applicationContext ?: return
        links.values.forEach { link ->
            val config = configs[link.id] ?: return@forEach
            val condition = config.enabledWhen

            // Check if link should be enabled based on network conditions
            if (!NetworkChecker.shouldEnable(ctx, condition)) {
                LogManager.appendLog("LINK", "Skipping ${link.id}: network conditions not met")
                return@forEach
            }

            try {
                if (!link.isConnected()) {
                    link.connect()
                }
            } catch (e: Exception) {
                LogManager.appendLog("LINK", "Failed to connect ${link.id}: ${e.message}")
            }
        }
    }

    /**
     * 重连所有链路
     */
    fun reconnectAll() {
        val ctx = applicationContext ?: return
        if (!isNetworkAvailable) {
            LogManager.appendLog("LINK", "Network unavailable, skipping reconnect")
            return
        }

        links.values.forEach { link ->
            val config = configs[link.id] ?: return@forEach

            // Check network conditions
            if (!NetworkChecker.shouldEnable(ctx, config.enabledWhen)) {
                LogManager.appendLog("LINK", "Skipping ${link.id}: network conditions not met")
                return@forEach
            }

            try {
                if (!link.isConnected()) {
                    if (link.connect()) {
                        LogManager.appendLog("LINK", "Reconnected ${link.id}")
                    }
                }
            } catch (e: Exception) {
                LogManager.appendLog("LINK", "Failed to reconnect ${link.id}: ${e.message}")
            }
        }
    }

    fun disconnectAll() {
        links.values.forEach { link ->
            try {
                link.disconnect()
            } catch (e: Exception) {
                LogManager.appendLog("LINK", "Error disconnecting ${link.id}: ${e.message}")
            }
        }
    }

    fun clear() {
        stopHealthCheck()
        unregisterNetworkCallback()
        disconnectAll()
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
                LogManager.appendLog("LINK", "Failed to unregister network callback: ${e.message}")
            }
            networkCallback = null
        }
    }

    private fun createLink(config: LinkConfig): Link {
        return when (config.type) {
            LinkType.mqtt -> MqttLink(config)
            LinkType.websocket -> WebSocketLink(config)
            LinkType.tcp -> TcpLink(config)
        }
    }

    fun getAllLinks(): Map<String, Link> = links.toMap()

    fun getLinkState(id: String): LinkState {
        val link = links[id] ?: return LinkState.disconnected
        return if (link.isConnected()) LinkState.connected else LinkState.disconnected
    }

    fun getNetworkInfo(): String {
        val ctx = applicationContext ?: return "No context"
        return NetworkChecker.getNetworkInfo(ctx)
    }

    fun isNetworkAvailable(): Boolean = isNetworkAvailable

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
