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
            LogManager.appendLog("WS", "WebSocket link not found: ${config.linkId}")
            callback?.invoke(false)
            return
        }

        // Ensure connected
        if (!link.isConnected()) {
            link.connect()
        }

        val success = link.send(item.data)
        callback?.invoke(success)
    }

    override fun isAvailable(): Boolean {
        val link = wsLink ?: return false
        return link.isConnected()
    }
}
