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
 * TCP 生产者输出
 *
 * 重试逻辑:
 *   retry.maxAttempts — 最大尝试次数（默认 1，即不重试）
 *   retry.interval    — 每次重试前等待时长
 *
 * 最终失败处理 (onFailure):
 *   action=discard   — 丢弃（默认）
 *   action=failQueue — 放入失败队列
 */
class TcpOutput(
    override val name: String,
    private val config: LinkOutputConfig
) : Output {

    override val type: OutputType = OutputType.tcp
    override val formatSteps get() = config.format

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val tcpLink: info.loveyu.mfca.link.TcpLink?
        get() = LinkManager.getLink(config.linkId) as? info.loveyu.mfca.link.TcpLink

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
                    LogManager.logDebug("TCP", "[$name] Retry $attempt/$maxAttempts after ${intervalMs}ms")
                    delay(intervalMs)
                }
            }

            LogManager.logWarn("TCP", "[$name] Exhausted all $maxAttempts attempt(s)")
            handleOnFailure(item)
            callback?.invoke(false)
        }
    }

    private fun doSend(item: QueueItem): Boolean {
        val link = tcpLink
        if (link == null) {
            LogManager.logError("TCP", "[$name] Link not found: ${config.linkId}")
            return false
        }

        if (!link.isConnected()) {
            if (!LinkManager.shouldEnableLink(config.linkId)) {
                LogManager.logDebug("TCP", "[$name] Skipping: link network conditions not met")
                return false
            }
            if (link.isConnecting()) {
                LogManager.logDebug("TCP", "[$name] Skipping: link is connecting")
                return false
            }
            link.connect()
            return false
        }

        if (LogManager.isDebugEnabled()) {
            LogManager.logDebug("TCP", "[$name] Sending dataLen=${item.data.size}")
        }
        return link.send(item.data)
    }

    private fun handleOnFailure(item: QueueItem) {
        val onFailure = config.onFailure ?: return
        if (onFailure.action == OnFailureAction.discard) return

        val idType = onFailure.idType
        if (idType.isNullOrBlank()) {
            LogManager.logWarn("TCP", "[$name] onFailure.action=failQueue but idType is empty")
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
                LogManager.logWarn("TCP", "[$name] Queue not found: $queueRef")
                false
            }
        } ?: false

        if (queued) {
            LogManager.logDebug("TCP", "[$name] Queued failed item (idType=$idType)")
        } else {
            LogManager.logWarn("TCP", "[$name] Failed to enqueue to fail queue")
        }
    }

    override fun isAvailable(): Boolean {
        val link = tcpLink ?: return false
        return link.isConnected()
    }
}
