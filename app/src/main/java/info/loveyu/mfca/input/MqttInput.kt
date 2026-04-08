package info.loveyu.mfca.input

import info.loveyu.mfca.config.LinkInputConfig
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.util.LogManager

/**
 * MQTT 消费者输入
 */
class MqttInput(
    private val config: LinkInputConfig
) : InputSource {

    override val inputName: String = config.name
    override val inputType: InputType = InputType.mqtt

    private var running = false
    private var messageListener: ((InputMessage) -> Unit)? = null

    private val mqttLink: info.loveyu.mfca.link.MqttLink?
        get() = LinkManager.getLink(config.linkId) as? info.loveyu.mfca.link.MqttLink

    override fun start() {
        val link = mqttLink
        if (link == null) {
            LogManager.appendLog("MQTT", "MQTT link not found: ${config.linkId}")
            return
        }

        // Subscribe to topic
        val topic = config.topic ?: return
        val qos = config.qos ?: 1

        if (!link.isConnected()) {
            link.connect()
        }

        link.setOnMessageListener { data ->
            val message = InputMessage(
                source = inputName,
                data = data,
                headers = emptyMap()
            )
            messageListener?.invoke(message)
        }

        link.subscribe(topic, qos)
        running = true
        LogManager.appendLog("MQTT", "MQTT input started: $inputName (topic: $topic, qos: $qos)")
    }

    override fun stop() {
        running = false
        LogManager.appendLog("MQTT", "MQTT input stopped: $inputName")
    }

    override fun isRunning(): Boolean = running

    override fun setOnMessageListener(listener: (InputMessage) -> Unit) {
        messageListener = listener
    }
}
