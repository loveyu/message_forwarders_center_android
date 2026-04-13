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

    // 获取要订阅的主题列表
    private fun getTopicsToSubscribe(): List<String> {
        val topics = config.topics ?: config.topic?.let { listOf(it) } ?: emptyList()
        return topics.filter { topic ->
            config.excludeTopics?.contains(topic) != true
        }
    }

    override fun start() {
        val link = mqttLink
        if (link == null) {
            LogManager.logError("MQTT", "Link not found: ${config.linkId} for input: $inputName")
            return
        }

        val topicsToSubscribe = getTopicsToSubscribe()
        if (topicsToSubscribe.isEmpty()) {
            LogManager.logWarn("MQTT", "No topics to subscribe for input: $inputName")
            return
        }

        val qos = config.qos ?: 1

        if (!link.isConnected()) {
            link.connect()
        }

        link.setOnMqttMessageListener { topic, msg ->
            // Check exclude_topics filter
            if (config.excludeTopics?.contains(topic) == true) {
                LogManager.logDebug("MQTT", "Excluded topic: $topic for $inputName")
                return@setOnMqttMessageListener
            }

            val message = InputMessage(
                source = inputName,
                data = msg.payload,
                headers = mapOf(
                    "mqtt_topic" to topic,
                    "mqtt_qos" to msg.qos.toString(),
                    "mqtt_retained" to msg.isRetained.toString()
                )
            )
            LogManager.log("MQTT", "Message received on topic=$topic for $inputName (${msg.payload.size} bytes)")
            if (messageListener != null) {
                messageListener!!.invoke(message)
            } else {
                LogManager.logWarn("MQTT", "No message listener for $inputName, message dropped (topic=$topic)")
            }
        }

        topicsToSubscribe.forEach { topic ->
            link.subscribe(topic, qos)
        }
        running = true
        LogManager.log("MQTT", "MQTT input started: $inputName (topics: $topicsToSubscribe, qos: $qos)")
    }

    override fun stop() {
        running = false
        LogManager.log("MQTT", "MQTT input stopped: $inputName")
    }

    override fun isRunning(): Boolean = running

    override fun setOnMessageListener(listener: (InputMessage) -> Unit) {
        messageListener = listener
    }
}
