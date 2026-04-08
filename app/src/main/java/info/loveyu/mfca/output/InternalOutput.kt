package info.loveyu.mfca.output

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import info.loveyu.mfca.config.InternalOutputConfig
import info.loveyu.mfca.config.InternalOutputType
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogManager
import java.io.File

/**
 * 内部输出 (剪贴板/文件/广播)
 */
class InternalOutput(
    private val context: Context,
    override val name: String,
    private val config: InternalOutputConfig
) : Output {

    override val type: OutputType = OutputType.internal

    private val clipboardManager: ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    companion object {
        private val broadcastReceivers = mutableMapOf<String, (Intent) -> Unit>()

        fun registerBroadcastReceiver(channel: String, callback: (Intent) -> Unit) {
            broadcastReceivers[channel] = callback
        }

        fun unregisterBroadcastReceiver(channel: String) {
            broadcastReceivers.remove(channel)
        }

        fun sendBroadcast(channel: String, intent: Intent) {
            broadcastReceivers[channel]?.invoke(intent)
        }
    }

    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        when (config.type) {
            InternalOutputType.clipboard -> sendToClipboard(item, callback)
            InternalOutputType.file -> sendToFile(item, callback)
            InternalOutputType.broadcast -> sendToBroadcast(item, callback)
        }
    }

    private fun sendToClipboard(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        try {
            val text = item.text
            val clip = ClipData.newPlainText("MessageForwarder", text)
            clipboardManager?.setPrimaryClip(clip)
            LogManager.appendLog("INTERNAL", "Written to clipboard: $name")
            callback?.invoke(true)
        } catch (e: Exception) {
            LogManager.appendLog("INTERNAL", "Clipboard write failed: ${e.message}")
            callback?.invoke(false)
        }
    }

    private fun sendToFile(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        try {
            val basePath = config.basePath ?: "/data/output"
            val options = config.options ?: emptyMap()

            val dir = File(basePath)
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val timestamp = System.currentTimeMillis()
            val extension = if (options["auto_extension"] == true) ".dat" else ""
            val fileName = "msg_$timestamp$extension"

            val file = File(dir, fileName)
            file.writeBytes(item.data)

            LogManager.appendLog("INTERNAL", "Written to file: ${file.absolutePath}")
            callback?.invoke(true)
        } catch (e: Exception) {
            LogManager.appendLog("INTERNAL", "File write failed: ${e.message}")
            callback?.invoke(false)
        }
    }

    private fun sendToBroadcast(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        try {
            val channel = config.channel ?: "global"

            val intent = Intent("info.loveyu.mfca.broadcast.$channel").apply {
                putExtra("data", item.data)
                putExtra("source", name)
                item.metadata.forEach { (key, value) ->
                    putExtra("meta_$key", value)
                }
            }

            // Send to local receivers (in-process)
            sendBroadcast(channel, intent)

            // Also send as system broadcast for other apps
            context.sendBroadcast(intent)

            LogManager.appendLog("INTERNAL", "Broadcast sent on channel: $channel")
            callback?.invoke(true)
        } catch (e: Exception) {
            LogManager.appendLog("INTERNAL", "Broadcast failed: ${e.message}")
            callback?.invoke(false)
        }
    }

    override fun isAvailable(): Boolean = true
}
