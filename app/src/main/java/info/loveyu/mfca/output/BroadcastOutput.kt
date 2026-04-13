package info.loveyu.mfca.output

import android.content.Context
import android.content.Intent
import info.loveyu.mfca.config.InternalOutputConfig
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogManager

/**
 * 广播输出
 */
class BroadcastOutput(
    private val context: Context,
    override val name: String,
    private val config: InternalOutputConfig
) : InternalOutput {
    override val type: OutputType = OutputType.internal

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
        try {
            val channel = config.channel ?: "global"

            if (LogManager.isDebugEnabled()) {
                LogManager.logDebug("BROADCAST", "send() - name=$name, channel=$channel, itemId=${item.id}, dataLen=${item.data.size}")
            }

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

            LogManager.log("INTERNAL", "Broadcast sent on channel: $channel, action=${intent.action}")
            callback?.invoke(true)
        } catch (e: Exception) {
            LogManager.logError("INTERNAL", "Broadcast failed: $name - ${e.message}")
            callback?.invoke(false)
        }
    }
}