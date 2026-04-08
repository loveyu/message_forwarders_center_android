package info.loveyu.mfca.input

import info.loveyu.mfca.config.LinkInputConfig
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.util.LogManager

/**
 * TCP 消费者输入
 */
class TcpInput(
    private val config: LinkInputConfig
) : InputSource {

    override val inputName: String = config.name
    override val inputType: InputType = InputType.tcp

    private var running = false
    private var messageListener: ((InputMessage) -> Unit)? = null

    private val tcpLink: info.loveyu.mfca.link.TcpLink?
        get() = LinkManager.getLink(config.linkId) as? info.loveyu.mfca.link.TcpLink

    override fun start() {
        val link = tcpLink
        if (link == null) {
            LogManager.appendLog("TCP", "TCP link not found: ${config.linkId}")
            return
        }

        link.setOnMessageListener { data ->
            val message = InputMessage(
                source = inputName,
                data = data,
                headers = emptyMap()
            )
            messageListener?.invoke(message)
        }

        if (!link.isConnected()) {
            link.connect()
        }

        running = true
        LogManager.appendLog("TCP", "TCP input started: $inputName")
    }

    override fun stop() {
        running = false
        LogManager.appendLog("TCP", "TCP input stopped: $inputName")
    }

    override fun isRunning(): Boolean = running

    override fun setOnMessageListener(listener: (InputMessage) -> Unit) {
        messageListener = listener
    }
}
