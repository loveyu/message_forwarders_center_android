package info.loveyu.mfca.output

import android.content.Context
import info.loveyu.mfca.clipboard.ClipboardHistoryDbHelper
import info.loveyu.mfca.config.InternalOutputConfig
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogManager

class ClipboardHistoryOutput(
    private val context: Context,
    override val name: String,
    private val config: InternalOutputConfig
) : InternalOutput {

    override val type: OutputType = OutputType.internal
    override val formatSteps get() = config.format

    private val historyDbHelper = ClipboardHistoryDbHelper(context)

    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        try {
            val text = item.text
            val contentType = ClipboardHistoryDbHelper.detectContentType(text)
            historyDbHelper.insertOrUpdate(text, contentType)
            if (LogManager.isDebugEnabled()) {
                LogManager.logDebug("CLIPBOARD", "History recorded: $name (len=${text.length}, type=$contentType)")
            }
            callback?.invoke(true)
        } catch (e: Exception) {
            LogManager.logError("INTERNAL", "ClipboardHistory failed: $name - ${e.message}")
            callback?.invoke(false)
        }
    }
}
