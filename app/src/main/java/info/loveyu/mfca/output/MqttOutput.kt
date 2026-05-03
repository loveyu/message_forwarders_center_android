package info.loveyu.mfca.output

import info.loveyu.mfca.config.LinkOutputConfig
import info.loveyu.mfca.config.OnFailureAction
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.queue.QueueManager
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MQTT 生产者输出
 *
 * 重试逻辑:
 *   retry.maxAttempts — 最大尝试次数（默认 1，即不重试）
 *   retry.interval    — 每次重试前等待时长（默认 1s）
 *
 * 最终失败处理 (onFailure):
 *   action=discard   — 丢弃（默认）
 *   action=failQueue — 放入失败队列，等待 FailQueueInput 在下次 tick 时重新注入
 *
 * MQTT 发布参数:
 *   qos    — QoS 级别 0/1/2（默认 1）
 *   retain — 是否设置 retain 标志（默认 false）
 */
class MqttOutput(
    override val name: String,
    private val config: LinkOutputConfig
) : Output {

    override val type: OutputType = OutputType.mqtt
    override val formatSteps get() = config.format

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val mqttLink: info.loveyu.mfca.link.MqttLink?
        get() = LinkManager.getLink(config.linkId) as? info.loveyu.mfca.link.MqttLink

    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        scope.launch {
            val maxAttempts = (config.retry?.maxAttempts ?: 1).coerceAtLeast(1)
            var attempt = 0

            while (attempt < maxAttempts) {
                val success = doSend(item)
                if (success) {
                    callback?.invoke(true)
                    return@launch
                }

                attempt++
                if (attempt < maxAttempts) {
                    val intervalMs = config.retry?.interval?.millis ?: 1_000L
                    LogManager.logDebug(
                        "MQTT",
                        "[$name] Retry $attempt/$maxAttempts after ${intervalMs}ms"
                    )
                    delay(intervalMs)
                }
            }

            LogManager.logWarn("MQTT", "[$name] Exhausted all $maxAttempts attempt(s)")
            handleOnFailure(item)
            callback?.invoke(false)
        }
    }

    private fun doSend(item: QueueItem): Boolean {
        val link = mqttLink
        if (link == null) {
            LogManager.logError("MQTT", "[$name] Link not found: ${config.linkId}")
            return false
        }

        val topic = config.topic
        if (topic == null) {
            LogManager.logWarn("MQTT", "[$name] No topic specified")
            return false
        }

        if (!link.isConnected()) {
            if (!LinkManager.shouldEnableLink(config.linkId)) {
                LogManager.logDebug("MQTT", "[$name] Skipping: link network conditions not met")
                return false
            }
            if (link.isConnecting()) {
                LogManager.logDebug("MQTT", "[$name] Skipping: link is connecting")
                return false
            }
            link.connect()
            return false
        }

        val qos = config.qos ?: 1
        val retain = config.retain
        if (LogManager.isDebugEnabled()) {
            LogManager.logDebug(
                "MQTT",
                "[$name] Publishing to $topic qos=$qos retain=$retain dataLen=${item.data.size}"
            )
        }
        return link.sendToTopic(topic, item.data, qos, retain)
    }

    private fun handleOnFailure(item: QueueItem) {
        val onFailure = config.onFailure ?: return
        if (onFailure.action == OnFailureAction.discard) return

        val idType = onFailure.idType
        if (idType.isNullOrBlank()) {
            LogManager.logWarn("MQTT", "[$name] onFailure.action=failQueue but idType is empty")
            return
        }

        val failItem = item.copy(
            tag = idType,
            metadata = item.metadata + mapOf(
                "idType" to idType,
                "outputName" to name,
                "source" to (item.metadata["source"] ?: ""),
                "rule" to (item.metadata["rule"] ?: ""),
                "failedAt" to System.currentTimeMillis().toString()
            ),
            retryCount = 0,
            nextAttemptAt = System.currentTimeMillis() + onFailure.delay.millis
        )

        val queued = onFailure.queue?.let { queueRef ->
            QueueManager.resolveQueue(queueRef)?.enqueue(failItem) ?: run {
                LogManager.logWarn("MQTT", "[$name] Queue not found: $queueRef")
                false
            }
        } ?: run {
            LogManager.logWarn("MQTT", "[$name] onFailure.action=failQueue but no queue specified")
            false
        }

        if (queued) {
            LogManager.logDebug(
                "MQTT",
                "[$name] Queued failed item (idType=$idType, delay=${onFailure.delay.value})"
            )
        } else {
            LogManager.logWarn("MQTT", "[$name] Failed to enqueue item to fail queue")
        }
    }

    override fun isAvailable(): Boolean {
        val link = mqttLink ?: return false
        return link.isConnected()
    }
}
