package info.loveyu.mfca.output

import android.content.Context
import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.config.InternalOutputConfig
import info.loveyu.mfca.config.InternalOutputType
import info.loveyu.mfca.queue.QueueManager
import info.loveyu.mfca.util.LogManager
import java.lang.ref.WeakReference
import kotlinx.coroutines.CompletableDeferred

/**
 * 输出管理器
 */
object OutputManager {

    private val outputs = mutableMapOf<String, Output>()
    private var contextRef: WeakReference<Context>? = null

    fun initialize(ctx: Context, config: AppConfig) {
        clear()
        contextRef = WeakReference(ctx.applicationContext)

        // HTTP outputs
        config.outputs.http.forEach { httpConfig ->
            outputs[httpConfig.name] = HttpOutput(httpConfig.name, httpConfig)
            LogManager.logDebug(
                "OUTPUT",
                "Registered HTTP output: ${httpConfig.name} -> ${httpConfig.url}"
            )
        }

        // Link-based outputs (MQTT, WebSocket, TCP)
        config.outputs.link.forEach { linkConfig ->
            if (linkConfig.linkIds.size <= 1) {
                val output = createLinkOutput(linkConfig)
                outputs[linkConfig.name] = output
                LogManager.logDebug("OUTPUT", "Registered ${linkConfig.role} output: ${linkConfig.name}")
            } else {
                // Fan-out: create one sub-output per linkId, each carrying the queue/onFailureQueue refs
                // so retries can happen independently per upstream.
                val subOutputs = linkConfig.linkIds.map { linkId ->
                    val subConfig = linkConfig.copy(
                        linkIds = listOf(linkId),
                        name = "${linkConfig.name}@${linkId}"
                    )
                    val subOutput = createLinkOutput(subConfig)
                    outputs[subConfig.name] = subOutput
                    subOutput
                }
                // MultiOutput has no queue itself — sub-outputs each have their own queue.
                val multiOutput = MultiOutput(linkConfig.name, subOutputs, queueRef = null)
                outputs[linkConfig.name] = multiOutput
                LogManager.logDebug("OUTPUT", "Registered fan-out ${linkConfig.role} output: ${linkConfig.name} -> ${linkConfig.linkIds}")
            }
        }

        // Internal outputs
        config.outputs.internal.forEach { internalConfig ->
            val output = createInternalOutput(internalConfig)
            outputs[internalConfig.name] = output
            LogManager.logDebug(
                "OUTPUT",
                "Registered internal output: ${internalConfig.name} (${internalConfig.type})"
            )
        }

        setupQueueConsumers(config)
    }

    fun getOutput(name: String): Output? = outputs[name]

    private fun setupQueueConsumers(config: AppConfig) {
        val wiredQueues = mutableSetOf<String>()

        fun wireQueue(queueName: String) {
            if (wiredQueues.add(queueName)) {
                QueueManager.getQueue(queueName)?.setConsumer { item ->
                    val outName = item.metadata["outputName"] ?: return@setConsumer false
                    val target = outputs[outName]
                    if (target == null) {
                        LogManager.logWarn("OUTPUT", "Queue consumer: output not found: $outName")
                        return@setConsumer false
                    }
                    // Mark item as being processed by queue — suppresses output-level onFailureQueue
                    val markedItem = item.copy(metadata = item.metadata + ("_inQueue" to "true"))
                    val result = CompletableDeferred<Boolean>()
                    target.send(markedItem) { success -> result.complete(success) }
                    result.await()
                } ?: LogManager.logWarn("OUTPUT", "Queue not found: $queueName")
            }
        }

        // Wire queues referenced by outputs (queueRef for async delivery)
        outputs.values.forEach { output ->
            output.queueRef?.name?.let { wireQueue(it) }
        }

        // Also wire onFailureQueue refs so failed items route back to the same output
        config.outputs.http.forEach { httpConfig ->
            httpConfig.onFailureQueue?.name?.let { wireQueue(it) }
        }
        config.outputs.link.forEach { linkConfig ->
            linkConfig.onFailureQueue?.name?.let { wireQueue(it) }
        }
    }

    fun getHttpOutput(name: String): HttpOutput? = outputs[name] as? HttpOutput

    fun getInternalOutput(name: String): InternalOutput? = outputs[name] as? InternalOutput

    fun getClipboardOutput(name: String): ClipboardOutput? = outputs[name] as? ClipboardOutput

    fun getClipboardHistoryOutput(name: String): ClipboardHistoryOutput? = outputs[name] as? ClipboardHistoryOutput

    fun getFileOutput(name: String): FileOutput? = outputs[name] as? FileOutput

    fun getBroadcastOutput(name: String): BroadcastOutput? = outputs[name] as? BroadcastOutput

    fun getNotifyOutput(name: String): NotifyOutput? = outputs[name] as? NotifyOutput

    fun clear() {
        outputs.clear()
    }

    fun getAllOutputs(): Map<String, Output> = outputs.toMap()

    /**
     * 刷入所有剪贴板输出的缓冲区（亮屏时调用）
     */
    fun flushAllClipboardOutputs() {
        outputs.values.forEach { output ->
            if (output is ClipboardOutput) {
                output.flushDeferred()
            }
        }
    }

    /**
     * 刷新所有文件输出的缓冲（tick 周期调用）
     */
    fun flushAllFileOutputs() {
        outputs.values.forEach { output ->
            if (output is FileOutput) {
                output.flushAll()
            }
        }
    }

    fun getOutputStatus(): Map<String, Boolean> {
        return outputs.mapValues { it.value.isAvailable() }
    }

    private fun createLinkOutput(config: info.loveyu.mfca.config.LinkOutputConfig): Output {
        val dsn = info.loveyu.mfca.link.LinkManager.getLinkConfig(config.linkId)?.dsn ?: config.linkId
        return when (info.loveyu.mfca.config.LinkType.fromDsn(dsn)) {
            info.loveyu.mfca.config.LinkType.websocket -> WebSocketOutput(config.name, config)
            info.loveyu.mfca.config.LinkType.tcp -> TcpOutput(config.name, config)
            else -> MqttOutput(config.name, config)
        }
    }

    private fun createInternalOutput(config: InternalOutputConfig): InternalOutput {
        val ctx = contextRef?.get() ?: throw IllegalStateException("OutputManager not initialized")
        return when (config.type) {
            InternalOutputType.clipboard -> ClipboardOutput(ctx, config.name, config)
            InternalOutputType.file -> FileOutput(ctx, config.name, config)
            InternalOutputType.broadcast -> BroadcastOutput(ctx, config.name, config)
            InternalOutputType.notify -> NotifyOutput(ctx, config.name, config)
            InternalOutputType.clipboardHistory -> ClipboardHistoryOutput(ctx, config.name, config)
        }
    }
}
