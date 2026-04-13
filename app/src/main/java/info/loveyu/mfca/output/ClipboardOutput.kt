package info.loveyu.mfca.output

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import info.loveyu.mfca.config.InternalOutputConfig
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogManager
import java.security.MessageDigest

/**
 * 剪贴板输出
 */
class ClipboardOutput(
    private val context: Context,
    override val name: String,
    private val config: InternalOutputConfig
) : InternalOutput {
    override val type: OutputType = OutputType.internal

    private val clipboardManager: ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    companion object {
        // 每个 output name 对应最后写入的内容和时间
        private val lastWrite = mutableMapOf<String, Pair<String, Long>>()
        private const val DEDUP_INTERVAL_MS = 30_000L // 30秒

        // 每个 output name 最多记录3条，内容为SHA1值
        private val logHistory = mutableMapOf<String, ArrayDeque<String>>()
        private const val MAX_LOG_ENTRIES = 3

        private fun sha1(text: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
            val hashBytes = digest.digest(text.toByteArray())
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }

    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        try {
            val text = item.text
            val now = System.currentTimeMillis()

            // 检查去重：内容相同且距离上次写入不足30秒则跳过
            val lastWriteEntry = lastWrite[name]
            if (lastWriteEntry != null && lastWriteEntry.first == text && now - lastWriteEntry.second < DEDUP_INTERVAL_MS) {
                LogManager.log("INTERNAL", "Clipboard skipped (duplicated): $name")
                callback?.invoke(true)
                return
            }

            // 记录日志（SHA1格式，最多3条）
            val contentHash = sha1(text)
            val history = logHistory.getOrPut(name) { ArrayDeque() }
            if (history.size >= MAX_LOG_ENTRIES) {
                history.removeFirst()
            }
            history.addLast(contentHash)
            LogManager.log("INTERNAL", "Clipboard content, hash: $contentHash, text: $text")

            // 写入剪贴板
            val clip = ClipData.newPlainText("MessageForwarder", text)
            clipboardManager?.setPrimaryClip(clip)
            lastWrite[name] = text to now
            LogManager.log("INTERNAL", "Written to clipboard: $name")
            callback?.invoke(true)
        } catch (e: Exception) {
            LogManager.log("INTERNAL", "Clipboard write failed: ${e.message}")
            callback?.invoke(false)
        }
    }
}