package info.loveyu.mfca.output

import android.content.Context
import info.loveyu.mfca.config.models.AppConfig
import info.loveyu.mfca.config.models.InternalOutputConfig
import info.loveyu.mfca.config.models.InternalOutputType
import info.loveyu.mfca.config.models.LinkOutputConfig
import info.loveyu.mfca.config.models.LinkType
import info.loveyu.mfca.config.models.OutputPluginConfig
import info.loveyu.mfca.output.clipboard.ClipboardHistoryOutput
import info.loveyu.mfca.output.clipboard.ClipboardOutput
import info.loveyu.mfca.output.file.FileOutput
import info.loveyu.mfca.output.http.HttpOutput
import info.loveyu.mfca.output.internal.BroadcastOutput
import info.loveyu.mfca.output.InternalOutput
import info.loveyu.mfca.output.mqtt.MqttOutput
import info.loveyu.mfca.output.notify.NotifyOutput
import info.loveyu.mfca.output.plugin.OutputPluginDispatcher
import info.loveyu.mfca.output.plugin.OutputSlot0
import info.loveyu.mfca.output.plugin.OutputSlot1
import info.loveyu.mfca.output.plugin.OutputSlot2
import info.loveyu.mfca.output.plugin.OutputSlot3
import info.loveyu.mfca.output.plugin.OutputSlot4
import info.loveyu.mfca.output.plugin.OutputSlot5
import info.loveyu.mfca.output.plugin.OutputSlot6
import info.loveyu.mfca.output.plugin.OutputSlot7
import info.loveyu.mfca.output.plugin.OutputSlot8
import info.loveyu.mfca.output.plugin.OutputSlot9
import info.loveyu.mfca.output.tcp.TcpOutput
import info.loveyu.mfca.output.tcp.WebSocketOutput
import info.loveyu.mfca.plugin.core.PluginEngine
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
    private var outputPluginEngine: PluginEngine? = null
    private val outputPluginDispatchers = mutableMapOf<String, OutputPluginDispatcher>()

    fun initialize(ctx: Context, config: AppConfig) {
        clear()
        contextRef = WeakReference(ctx.applicationContext)

        // ── Init output plugin engine ──
        val engine = createOutputPluginEngine(ctx, config)
        outputPluginEngine = engine

        // HTTP outputs
        config.outputs.http.forEach { httpConfig ->
            val ctx = contextRef?.get() ?: return
            outputs[httpConfig.name] = HttpOutput(ctx, httpConfig.name, httpConfig)
            createOutputDispatcher(httpConfig.name, httpConfig.plugins, engine)?.let {
                outputPluginDispatchers[httpConfig.name] = it
            }
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
                createOutputDispatcher(linkConfig.name, linkConfig.plugins, engine)?.let {
                    outputPluginDispatchers[linkConfig.name] = it
                }
                LogManager.logDebug("OUTPUT", "Registered ${linkConfig.role} output: ${linkConfig.name}")
            } else {
                val subOutputs = linkConfig.linkIds.map { linkId ->
                    val subConfig = linkConfig.copy(
                        linkIds = listOf(linkId),
                        name = "${linkConfig.name}@${linkId}"
                    )
                    val subOutput = createLinkOutput(subConfig)
                    outputs[subConfig.name] = subOutput
                    subOutput
                }
                val multiOutput = MultiOutput(linkConfig.name, subOutputs, queueRef = null)
                outputs[linkConfig.name] = multiOutput
                LogManager.logDebug("OUTPUT", "Registered fan-out ${linkConfig.role} output: ${linkConfig.name} -> ${linkConfig.linkIds}")
            }
        }

        // Internal outputs
        config.outputs.internal.forEach { internalConfig ->
            val output = createInternalOutput(internalConfig)
            outputs[internalConfig.name] = output
            createOutputDispatcher(internalConfig.name, internalConfig.plugins, engine)?.let {
                outputPluginDispatchers[internalConfig.name] = it
            }
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

    fun getOutputPluginDispatcher(name: String): OutputPluginDispatcher? =
        outputPluginDispatchers[name]

    fun clear() {
        outputPluginEngine?.unloadAll()
        outputPluginEngine = null
        outputPluginDispatchers.clear()
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

    private fun createOutputPluginEngine(ctx: Context, config: AppConfig): PluginEngine? {
        val allPluginConfigs = (config.outputs.http.mapNotNull { it.plugins } +
            config.outputs.link.mapNotNull { it.plugins } +
            config.outputs.internal.mapNotNull { it.plugins })
        val allSlots = allPluginConfigs.flatMap { it.front.slots }.distinct()
        if (allSlots.isEmpty()) return null

        val engine = PluginEngine(ctx, "Output") { slot ->
            when (slot) {
                0 -> OutputSlot0()
                1 -> OutputSlot1()
                2 -> OutputSlot2()
                3 -> OutputSlot3()
                4 -> OutputSlot4()
                5 -> OutputSlot5()
                6 -> OutputSlot6()
                7 -> OutputSlot7()
                8 -> OutputSlot8()
                else -> OutputSlot9()
            }
        }
        engine.loadConfiguredSlots(allSlots)
        return engine
    }

    private fun createOutputDispatcher(
        name: String,
        pluginConfig: OutputPluginConfig?,
        engine: PluginEngine?,
    ): OutputPluginDispatcher? {
        if (engine == null || pluginConfig == null || !pluginConfig.enabled) return null
        if (pluginConfig.front.slots.isEmpty()) return null
        return OutputPluginDispatcher(engine, pluginConfig)
    }

    private fun createLinkOutput(config: LinkOutputConfig): Output {
        val ctx = contextRef?.get() ?: throw IllegalStateException("OutputManager not initialized")
        val dsn = info.loveyu.mfca.link.LinkManager.getLinkConfig(config.linkId)?.dsn ?: config.linkId
        return when (LinkType.fromDsn(dsn)) {
            LinkType.websocket -> WebSocketOutput(ctx, config.name, config)
            LinkType.tcp -> TcpOutput(ctx, config.name, config)
            else -> MqttOutput(ctx, config.name, config)
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
