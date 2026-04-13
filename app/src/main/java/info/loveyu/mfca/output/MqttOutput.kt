package info.loveyu.mfca.output

import info.loveyu.mfca.config.LinkOutputConfig
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogManager

/**
 * MQTT 生产者输出
 */
class MqttOutput(
    override val name: String,
    private val config: LinkOutputConfig
) : Output {

    override val type: OutputType = OutputType.mqtt

    private val mqttLink: info.loveyu.mfca.link.MqttLink?
        get() = LinkManager.getLink(config.linkId) as? info.loveyu.mfca.link.MqttLink

    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        val link = mqttLink
        if (link == null) {
            LogManager.logError("MQTT", "Link not found: ${config.linkId} for output: $name")
            callback?.invoke(false)
            return
        }

        val topic = config.topic
        if (topic == null) {
            LogManager.logWarn("MQTT", "No topic specified for output: $name")
            callback?.invoke(false)
            return
        }

        // Ensure connected
        if (!link.isConnected()) {
            link.connect()
        }

        if (LogManager.isDebugEnabled()) {
            LogManager.logDebug("MQTT", "Sending to topic: $topic, qos=${config.qos ?: 1}, dataLen=${item.data.size}")
        }

        val qos = config.qos ?: 1
        val success = link.sendToTopic(topic, item.data, qos)
        if (success) {
            LogManager.log("MQTT", "Sent to topic: $topic via $name")
        } else {
            LogManager.logWarn("MQTT", "Failed to send to topic: $topic via $name")
        }
        callback?.invoke(success)
    }

    override fun isAvailable(): Boolean {
        val link = mqttLink ?: return false
        return link.isConnected()
    }
}

private val info.loveyu.mfca.config.LinkOutputConfig.qos: Int
    get() = 1 // Default QoS
