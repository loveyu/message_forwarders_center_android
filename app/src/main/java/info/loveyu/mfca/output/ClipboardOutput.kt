package info.loveyu.mfca.output

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import info.loveyu.mfca.config.Duration
import info.loveyu.mfca.config.InternalOutputConfig
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogManager
import java.security.MessageDigest

/**
 * 剪贴板输出
 *
 * 亮屏模式：实时写入剪贴板
 * 熄屏模式：缓冲消息，亮屏时刷入（延迟应用）
 */
class ClipboardOutput(
    private val context: Context,
    override val name: String,
    private val config: InternalOutputConfig
) : InternalOutput {
    override val type: OutputType = OutputType.internal

    private val clipboardManager: ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    // 熄屏延迟写入配置
    private val deferOnScreenOff: Boolean
    private val maxDeferredItems: Int
    private val deferTtlMs: Long

    // 熄屏缓冲区
    private val deferredItems = mutableListOf<QueueItem>()
    private val bufferLock = Any()

    init {
        val opts = config.options
        if (opts != null) {
            deferOnScreenOff = (opts["deferOnScreenOff"] as? Boolean)
                ?: opts["deferOnScreenOff"]?.toString()?.toBoolean()
                ?: true
            maxDeferredItems = (opts["maxDeferredItems"] as? Number)?.toInt()
                ?: opts["maxDeferredItems"]?.toString()?.toIntOrNull()
                ?: 1
            val ttlStr = opts["deferTtl"] as? String ?: "5m"
            deferTtlMs = Duration(ttlStr).millis
        } else {
            deferOnScreenOff = true
            maxDeferredItems = 1
            deferTtlMs = Duration("5m").millis
        }
    }

    companion object {
        /**
         * 屏幕状态：由 ForwardService 广播事件驱动更新，send() 热路径只读 volatile boolean。
         * 避免每次 send() 调用 PowerManager.isInteractive 的 binder IPC 开销。
         */
        @Volatile
        var isScreenOn: Boolean = true
            private set

        /** 由 ForwardService 在 SCREEN_ON/USER_PRESENT 时调用 */
        fun notifyScreenOn() {
            isScreenOn = true
        }

        /** 由 ForwardService 在 SCREEN_OFF 时调用 */
        fun notifyScreenOff() {
            isScreenOn = false
        }

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

            if (LogManager.isDebugEnabled()) {
                LogManager.logDebug("CLIPBOARD", "send() - name=$name, itemId=${item.id}, dataLen=${item.data.size}")
            }

            // 检查去重：内容相同且距离上次写入不足30秒则跳过
            val lastWriteEntry = lastWrite[name]
            if (lastWriteEntry != null && lastWriteEntry.first == text && now - lastWriteEntry.second < DEDUP_INTERVAL_MS) {
                LogManager.logWarn("INTERNAL", "Clipboard skipped (duplicated): $name")
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

            // 熄屏检测：延迟写入（读取 volatile boolean，零开销）
            if (deferOnScreenOff && !isScreenOn) {
                bufferItem(item)
                callback?.invoke(true)
                return
            }

            // 亮屏：立即写入
            writeToClipboard(text, contentHash)
            callback?.invoke(true)
        } catch (e: Exception) {
            LogManager.logError("INTERNAL", "Clipboard write failed: $name - ${e.message}")
            callback?.invoke(false)
        }
    }

    /**
     * 缓冲消息，等待亮屏后刷入
     */
    private fun bufferItem(item: QueueItem) {
        synchronized(bufferLock) {
            // 淘汰过期项
            val cutoff = System.currentTimeMillis() - deferTtlMs
            deferredItems.removeAll { it.enqueuedAt < cutoff }

            // 容量控制：保留最新 N 条
            while (deferredItems.size >= maxDeferredItems) {
                deferredItems.removeAt(0)
            }

            deferredItems.add(item)
        }
        LogManager.log("INTERNAL", "Clipboard deferred: $name (buffered=${deferredItems.size}, max=$maxDeferredItems)")
    }

    /**
     * 亮屏时刷入缓冲区中最新的一条消息
     */
    fun flushDeferred() {
        val itemsToFlush: List<QueueItem>
        synchronized(bufferLock) {
            if (deferredItems.isEmpty()) return

            // 再次淘汰过期项
            val cutoff = System.currentTimeMillis() - deferTtlMs
            deferredItems.removeAll { it.enqueuedAt < cutoff }

            if (deferredItems.isEmpty()) return

            itemsToFlush = deferredItems.toList()
            deferredItems.clear()
        }

        // 只写入最新一条（剪贴板是单槽目标）
        val latestItem = itemsToFlush.last()
        val text = latestItem.text
        val contentHash = sha1(text)
        writeToClipboard(text, contentHash)
        LogManager.log("INTERNAL", "Clipboard flushed deferred: $name (${itemsToFlush.size} buffered, wrote latest)")
    }

    /**
     * 写入剪贴板
     */
    private fun writeToClipboard(text: String, contentHash: String) {
        val clip = ClipData.newPlainText("MessageForwarder", text)
        clipboardManager?.setPrimaryClip(clip)
        lastWrite[name] = text to System.currentTimeMillis()
        LogManager.log("INTERNAL", "Written to clipboard: $name (hash=$contentHash, len=${text.length})")
    }
}
