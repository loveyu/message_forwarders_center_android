package info.loveyu.mfca.input

import info.loveyu.mfca.config.LinkInputConfig
import info.loveyu.mfca.link.LinkManager
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
        LogManager.logDebug("WSINPUT", "WebSocketInput.start called: inputName=$inputName, linkId=${config.linkId}")
        val link = wsLink
        if (link == null) {
            LogManager.logError("WS", "Link not found: ${config.linkId} for input: $inputName")
            return
        }

        link.setOnMessageListener { data ->
            LogManager.logDebug("WSINPUT", "listener invoked: inputName=$inputName")
            val message = InputMessage(
                source = inputName,
                data = data,
                headers = emptyMap()
            )
            LogManager.log("WS", "Message received for $inputName (${data.size} bytes)")
            if (messageListener != null) {
                messageListener!!.invoke(message)
            } else {
                LogManager.logWarn("WS", "No message listener for $inputName, message dropped")
            }
        }

        if (!link.isConnected()) {
            link.connect()
        }

        running = true
        LogManager.log("WS", "WebSocket input started: $inputName")
    }

    override fun stop() {
        running = false
        LogManager.log("WS", "WebSocket input stopped: $inputName")
    }

    override fun isRunning(): Boolean = running

    override fun setOnMessageListener(listener: (InputMessage) -> Unit) {
        LogManager.logDebug("WSINPUT", "setOnMessageListener called for $inputName")
        messageListener = listener
    }
}
