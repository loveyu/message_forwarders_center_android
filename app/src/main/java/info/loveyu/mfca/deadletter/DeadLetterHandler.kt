package info.loveyu.mfca.deadletter

import android.content.Context
import info.loveyu.mfca.config.DeadLetterConfig
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 死信队列处理器
 */
class DeadLetterHandler(
    private val context: Context,
    private val config: DeadLetterConfig
) {
    private val deadLetterQueue = mutableListOf<DeadLetterItem>()

    init {
        if (config.enabled) {
            LogManager.appendLog("DLQ", "Dead letter queue enabled (max_retry: ${config.maxRetry})")
        }
    }

    fun handle(item: QueueItem, error: String) {
        if (!config.enabled) {
            return
        }

        val deadLetterItem = DeadLetterItem(
            originalItem = item,
            errorMessage = error,
            failedAt = System.currentTimeMillis(),
            retryCount = item.retryCount
        )

        deadLetterQueue.add(deadLetterItem)
        LogManager.appendLog("DLQ", "Item added to dead letter queue: $error")

        // Process dead letter actions
        processDeadLetterActions(deadLetterItem)

        // Save to file
        saveDeadLetterToFile(deadLetterItem)
    }

    private fun processDeadLetterActions(item: DeadLetterItem) {
        if (config.action.isEmpty()) {
            LogManager.appendLog("DLQ", "No dead letter actions configured")
            return
        }

        config.action.forEach { step ->
            step.to.forEach { outputName ->
                val output = OutputManager.getOutput(outputName)
                if (output != null) {
                    val dlqItem = QueueItem(
                        data = item.originalItem.data,
                        metadata = item.originalItem.metadata + mapOf(
                            "dlq_error" to item.errorMessage,
                            "dlq_failed_at" to item.failedAt.toString()
                        )
                    )
                    output.send(dlqItem, null)
                    LogManager.appendLog("DLQ", "Dead letter sent to output: $outputName")
                }
            }
        }
    }

    private fun saveDeadLetterToFile(item: DeadLetterItem) {
        try {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "dead_letters")
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "dlq_$timestamp.txt")

            val content = buildString {
                appendLine("Dead Letter Item")
                appendLine("===============")
                appendLine("Failed at: ${Date(item.failedAt)}")
                appendLine("Error: ${item.errorMessage}")
                appendLine("Retry count: ${item.retryCount}")
                appendLine()
                appendLine("Data:")
                appendLine(String(item.originalItem.data))
            }

            file.writeText(content)
            LogManager.appendLog("DLQ", "Dead letter saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            LogManager.appendLog("DLQ", "Failed to save dead letter: ${e.message}")
        }
    }

    fun getDeadLetterCount(): Int = deadLetterQueue.size

    fun clearDeadLetters() {
        deadLetterQueue.clear()
        LogManager.appendLog("DLQ", "Dead letters cleared")
    }
}

data class DeadLetterItem(
    val originalItem: QueueItem,
    val errorMessage: String,
    val failedAt: Long,
    val retryCount: Int
)
