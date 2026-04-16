package info.loveyu.mfca.output

import info.loveyu.mfca.config.LinkOutputConfig
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogManager

/**
 * WebSocket 生产者输出
 */
class WebSocketOutput(
    override val name: String,
    private val config: LinkOutputConfig
) : Output {

    override val type: OutputType = OutputType.websocket

    private val wsLink: info.loveyu.mfca.link.WebSocketLink?
        get() = LinkManager.getLink(config.linkId) as? info.loveyu.mfca.link.WebSocketLink

    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        val link = wsLink
        if (link == null) {
            LogManager.logError("WS", "Link not found: ${config.linkId} for output: $name")
            callback?.invoke(false)
            return
        }

        // Ensure connected
        if (!link.isConnected()) {
            if (!LinkManager.shouldEnableLink(config.linkId)) {
                LogManager.logDebug("WS", "Skipping send via $name: link network conditions not met")
                callback?.invoke(false)
                return
            }
            if (link.isConnecting()) {
                LogManager.logDebug("WS", "Skipping send via $name: link is connecting")
                callback?.invoke(false)
                return
            }
            link.connect()
        }

        if (LogManager.isDebugEnabled()) {
            LogManager.logDebug("WS", "Sending data: dataLen=${item.data.size}")
        }

        val success = link.send(item.data)
        if (success) {
            LogManager.log("WS", "Data sent via $name")
        } else {
            LogManager.logWarn("WS", "Failed to send via $name")
        }
        callback?.invoke(success)
    }

    override fun isAvailable(): Boolean {
        val link = wsLink ?: return false
        return link.isConnected()
    }
}
