package info.loveyu.mfca.link

import android.content.Context
import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.NetworkChecker

/**
 * 链接池管理器
 */
object LinkManager {

    private val links = mutableMapOf<String, Link>()
    private val configs = mutableMapOf<String, LinkConfig>()
    private var applicationContext: Context? = null

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
        disconnectAll()
        links.clear()
        configs.clear()
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
}
