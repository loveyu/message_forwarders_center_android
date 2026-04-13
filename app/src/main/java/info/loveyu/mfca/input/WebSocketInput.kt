package info.loveyu.mfca.input

import info.loveyu.mfca.config.LinkInputConfig
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.util.LogLevel
import info.loveyu.mfca.util.LogManager

/**
 * WebSocket 消费者输入
 */
class WebSocketInput(
    private val config: LinkInputConfig
) : InputSource {

    override val inputName: String = config.name
    override val inputType: InputType = InputType.websocket

    private var running = false
    private var messageListener: ((InputMessage) -> Unit)? = null

    private val wsLink: info.loveyu.mfca.link.WebSocketLink?
        get() = LinkManager.getLink(config.linkId) as? info.loveyu.mfca.link.WebSocketLink

    override fun start() {
        LogManager.appendLog(LogLevel.DEBUG, "WSINPUT", "WebSocketInput.start called: inputName=$inputName, linkId=${config.linkId}")
        val link = wsLink
        if (link == null) {
            LogManager.appendLog("WS", "WebSocket link not found: ${config.linkId}")
            return
        }

        link.setOnMessageListener { data ->
            LogManager.appendLog(LogLevel.DEBUG, "WSINPUT", "WebSocketInput.listener invoked: inputName=$inputName")
            val message = InputMessage(
                source = inputName,
                data = data,
                headers = emptyMap()
            )
            LogManager.appendLog("WS", "Message received: ${String(data).take(100)}")
            messageListener?.invoke(message)
            LogManager.appendLog(LogLevel.DEBUG, "WSINPUT", "messageListener invoked, inputName=$inputName, listener=${messageListener != null}")
        }

        if (!link.isConnected()) {
            link.connect()
        }

        running = true
        LogManager.appendLog("WS", "WebSocket input started: $inputName")
    }

    override fun stop() {
        running = false
        LogManager.appendLog("WS", "WebSocket input stopped: $inputName")
    }

    override fun isRunning(): Boolean = running

    override fun setOnMessageListener(listener: (InputMessage) -> Unit) {
        LogManager.appendLog(LogLevel.DEBUG, "WSINPUT", "setOnMessageListener called for $inputName, listener=$listener")
        messageListener = listener
    }
}
