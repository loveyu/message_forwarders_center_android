package info.loveyu.mfca.output

import info.loveyu.mfca.config.LinkOutputConfig
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
 * 最终失败处理 (onFailureQueue):
 *   将消息放入失败队列异步重试，队列消费者会路由回本输出
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
    override val queueRef get() = config.queue

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val mqttLink: info.loveyu.mfca.link.MqttLink?
        get() = LinkManager.getLink(config.linkId) as? info.loveyu.mfca.link.MqttLink

    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        scope.launch {
            val maxAttempts = (config.retry?.maxAttempts ?: 1).coerceAtLeast(1)
            var attempt = 0

            while (attempt < maxAttempts) {
                when (doSend(item)) {
                    SendResult.SUCCESS -> {
                        callback?.invoke(true)
                        return@launch
                    }
                    SendResult.SKIP -> {
                        callback?.invoke(false)
                        return@launch
                    }
                    SendResult.RETRY -> {}
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
            handleOnFailureQueue(item)
            callback?.invoke(false)
        }
    }

    private fun doSend(item: QueueItem): SendResult {
        val link = mqttLink
        if (link == null) {
            LogManager.logError("MQTT", "[$name] Link not found: ${config.linkId}")
            return SendResult.RETRY
        }

        val topic = config.topic
        if (topic == null) {
            LogManager.logWarn("MQTT", "[$name] No topic specified")
            return SendResult.RETRY
        }

        if (!link.isConnected()) {
            if (!LinkManager.shouldEnableLink(config.linkId)) {
                LogManager.logDebug("MQTT", "[$name] Skipping: link network conditions not met")
                return SendResult.SKIP
            }
            if (link.isConnecting()) {
                LogManager.logDebug("MQTT", "[$name] Skipping: link is connecting")
                return SendResult.RETRY
            }
            link.connect()
            return SendResult.RETRY
        }

        val qos = config.qos ?: 1
        val retain = config.retain
        if (LogManager.isDebugEnabled()) {
            LogManager.logDebug(
                "MQTT",
                "[$name] Publishing to $topic qos=$qos retain=$retain dataLen=${item.data.size}"
            )
        }
        return if (link.sendToTopic(topic, item.data, qos, retain)) SendResult.SUCCESS else SendResult.RETRY
    }

    private fun handleOnFailureQueue(item: QueueItem) {
        // If item is being processed by the queue layer, skip — retry is handled by the queue
        if (item.metadata["_inQueue"] == "true" || item.isDeadLetter) return
        val queueRef = config.onFailureQueue ?: return

        val failItem = item.copy(
            metadata = item.metadata + mapOf(
                "outputName" to name,
                "failedAt" to System.currentTimeMillis().toString()
            ),
            retryCount = 0,
            nextAttemptAt = System.currentTimeMillis() + queueRef.delay.millis
        )

        val queue = QueueManager.getQueue(queueRef.name)
        val queued = queue?.enqueue(failItem) ?: run {
            LogManager.logWarn("MQTT", "[$name] onFailureQueue not found: ${queueRef.name}")
            false
        }

        if (queued) {
            LogManager.logDebug("MQTT", "[$name] Queued failed item to onFailureQueue=${queueRef.name}")
        } else {
            LogManager.logWarn("MQTT", "[$name] Failed to enqueue item to onFailureQueue: ${queueRef.name}")
        }
    }

    override fun isAvailable(): Boolean {
        val link = mqttLink ?: return false
        return link.isConnected()
    }
}
