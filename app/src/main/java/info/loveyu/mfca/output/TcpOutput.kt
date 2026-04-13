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
            LogManager.log("TCP", "TCP link not found: ${config.linkId}")
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
        val link = tcpLink ?: return false
        return link.isConnected()
    }
}
