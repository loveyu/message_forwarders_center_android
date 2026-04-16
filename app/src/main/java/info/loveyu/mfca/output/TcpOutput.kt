package info.loveyu.mfca.output

import info.loveyu.mfca.config.LinkOutputConfig
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogManager

/**
 * TCP 生产者输出
 */
class TcpOutput(
    override val name: String,
    private val config: LinkOutputConfig
) : Output {

    override val type: OutputType = OutputType.tcp

    private val tcpLink: info.loveyu.mfca.link.TcpLink?
        get() = LinkManager.getLink(config.linkId) as? info.loveyu.mfca.link.TcpLink

    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        val link = tcpLink
        if (link == null) {
            LogManager.logError("TCP", "Link not found: ${config.linkId} for output: $name")
            callback?.invoke(false)
            return
        }

        // Ensure connected
        if (!link.isConnected()) {
            if (!LinkManager.shouldEnableLink(config.linkId)) {
                LogManager.logDebug("TCP", "Skipping send via $name: link network conditions not met")
                callback?.invoke(false)
                return
            }
            if (link.isConnecting()) {
                LogManager.logDebug("TCP", "Skipping send via $name: link is connecting")
                callback?.invoke(false)
                return
            }
            link.connect()
        }

        if (LogManager.isDebugEnabled()) {
            LogManager.logDebug("TCP", "Sending data: dataLen=${item.data.size}")
        }

        val success = link.send(item.data)
        if (success) {
            LogManager.log("TCP", "Data sent via $name")
        } else {
            LogManager.logWarn("TCP", "Failed to send via $name")
        }
        callback?.invoke(success)
    }

    override fun isAvailable(): Boolean {
        val link = tcpLink ?: return false
        return link.isConnected()
    }
}
