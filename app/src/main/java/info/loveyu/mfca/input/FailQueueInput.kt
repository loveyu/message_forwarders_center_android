package info.loveyu.mfca.input

import info.loveyu.mfca.config.FailQueueInputConfig
import info.loveyu.mfca.queue.QueueManager
import info.loveyu.mfca.util.LogManager

/**
 * 失败队列输入源
 *
 * 由统一 Ticker 驱动（非持续监听），在每次 tick 时从失败队列中按 idType 取出消息，
 * 并重新注入规则引擎。适用于"输出失败后重试"的场景。
 *
 * 注入消息格式:
 *   - source  : 该 FailQueueInput 的 name
 *   - data    : 原始失败消息的字节内容（与原始输出时相同）
 *   - headers : 携带失败元信息
 *       X-IdType     — 失败消息类型（对应 onFailure.idType）
 *       X-OutputName — 发出失败的输出源名称
 *       X-Source     — 原始输入源名称
 *       X-Rule       — 触发失败的规则名
 *       X-EnqueuedAt — 入队时间戳（毫秒）
 *       X-FailedAt   — 输出失败时间戳（毫秒）
 *       X-RetryCount — 该消息已被重试的次数
 *
 * 使用 idTypes 订阅:
 *   idTypes 为空时处理队列中所有消息；非空时只处理 tag 在 idTypes 中的消息。
 */
class FailQueueInput(
    private val config: FailQueueInputConfig,
    private val messageHandler: (InputMessage) -> Unit
) : InputSource {

    override val inputName: String = config.name
    override val inputType: InputType = InputType.failQueue

    @Volatile
    private var running = false

    override fun start() {
        running = true
        LogManager.logDebug("FAILQ", "FailQueueInput [${config.name}] started (idTypes=${config.idTypes})")
    }

    override fun stop() {
        running = false
        LogManager.logDebug("FAILQ", "FailQueueInput [${config.name}] stopped")
    }

    override fun isRunning(): Boolean = running

    override fun setOnMessageListener(listener: (InputMessage) -> Unit) {
        // Listener is set at construction; this method is no-op for FailQueueInput.
        // Message delivery happens via messageHandler passed to constructor.
    }

    /**
     * 由 InputManager.onTick() 调用。
     * 从配置的队列中按 idType 批量取出到期消息，注入规则引擎。
     */
    fun onTick() {
        if (!running) return

        val queues = buildList {
            config.sqliteQueue?.let { QueueManager.getSqliteQueue(it) }?.let { add(it) }
            config.memoryQueue?.let { QueueManager.getMemoryQueue(it) }?.let { add(it) }
        }

        if (queues.isEmpty()) {
            LogManager.logDebug("FAILQ", "[${config.name}] No queues configured")
            return
        }

        val tags = config.idTypes
        var processed = 0

        for (queue in queues) {
            while (processed < config.batchSize) {
                val item = (if (tags.isEmpty()) queue.dequeue() else queue.dequeueByTag(tags))
                    ?: break

                val headers = buildMap<String, String> {
                    put("X-IdType", item.tag.ifEmpty { item.metadata["idType"] ?: "" })
                    item.metadata["outputName"]?.let { put("X-OutputName", it) }
                    item.metadata["source"]?.let { put("X-Source", it) }
                    item.metadata["rule"]?.let { put("X-Rule", it) }
                    put("X-EnqueuedAt", item.enqueuedAt.toString())
                    item.metadata["failedAt"]?.let { put("X-FailedAt", it) }
                    put("X-RetryCount", item.retryCount.toString())
                }

                val msg = InputMessage(
                    source = inputName,
                    data = item.data,
                    headers = headers
                )

                LogManager.logDebug(
                    "FAILQ",
                    "[${config.name}] Re-injecting item idType=${headers["X-IdType"]} dataLen=${item.data.size}"
                )
                messageHandler(msg)
                processed++
            }
        }

        if (processed > 0) {
            LogManager.logDebug("FAILQ", "[${config.name}] Processed $processed item(s) from fail queue")
        }
    }
}
