package info.loveyu.mfca.deadletter

import android.content.Context
import info.loveyu.mfca.config.DeadLetterConfig
import info.loveyu.mfca.input.InputMessage
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 死信队列处理器（单例）
 *
 * 消息经过队列最大重试次数后进入死信，交由 pipelineRunner 处理。
 * 死信消息标记 isDeadLetter=true，不再允许入队。
 */
object DeadLetterHandler {
    private var context: Context? = null
    private var config: DeadLetterConfig = DeadLetterConfig()

    /** 死信消息处理器，由 ForwardService 注入，通常是 RuleEngine.processDeadLetter */
    private var pipelineRunner: ((InputMessage) -> Unit)? = null

    fun initialize(
        ctx: Context,
        cfg: DeadLetterConfig,
        runner: (InputMessage) -> Unit
    ) {
        context = ctx
        config = cfg
        pipelineRunner = runner
        if (cfg.enabled) {
            LogManager.logInfo("DLQ", "Dead letter handler initialized (maxRetry=${cfg.maxRetry})")
        }
    }

    fun clear() {
        context = null
        config = DeadLetterConfig()
        pipelineRunner = null
    }

    fun handle(item: QueueItem, queueName: String, error: String) {
        if (!config.enabled) return

        LogManager.logWarn("DLQ", "Dead letter from queue=$queueName retries=${item.retryCount}: $error")

        // Save to file for audit trail
        saveDeadLetterToFile(item, queueName, error)

        // Run pipeline with dead-letter marked message
        val runner = pipelineRunner
        if (runner != null && config.pipeline.isNotEmpty()) {
            val dlMeta = item.metadata + mapOf(
                "dlq_queue" to queueName,
                "dlq_error" to error,
                "dlq_retries" to item.retryCount.toString(),
                "dlq_failed_at" to System.currentTimeMillis().toString()
            )
            val msg = InputMessage(
                data = item.data,
                source = "deadletter:$queueName",
                metadata = dlMeta,
                headers = item.headers,
                isDeadLetter = true
            )
            try {
                runner(msg)
            } catch (e: Exception) {
                LogManager.logWarn("DLQ", "Dead letter pipeline failed: ${e.message}")
            }
        } else if (runner == null) {
            LogManager.logWarn("DLQ", "No dead letter pipeline runner configured")
        }
    }

    private fun saveDeadLetterToFile(item: QueueItem, queueName: String, error: String) {
        val ctx = context ?: return
        try {
            val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "dead_letters")
            dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val file = File(dir, "dlq_${timestamp}.txt")

            val content = buildString {
                appendLine("Dead Letter Item")
                appendLine("===============")
                appendLine("Failed at: ${Date()}")
                appendLine("Queue: $queueName")
                appendLine("Error: $error")
                appendLine("Retry count: ${item.retryCount}")
                appendLine("Tag: ${item.tag}")
                appendLine()
                appendLine("Metadata: ${item.metadata}")
                appendLine()
                appendLine("Data:")
                appendLine(String(item.data))
            }

            file.writeText(content)
            LogManager.logDebug("DLQ", "Dead letter saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            LogManager.logWarn("DLQ", "Failed to save dead letter file: ${e.message}")
        }
    }
}

data class DeadLetterItem(
    val originalItem: QueueItem,
    val errorMessage: String,
    val failedAt: Long,
    val retryCount: Int
)
