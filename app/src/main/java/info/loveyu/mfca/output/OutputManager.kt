package info.loveyu.mfca.output

import android.content.Context
import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.config.InternalOutputConfig
import info.loveyu.mfca.config.InternalOutputType
import info.loveyu.mfca.util.LogManager
import java.lang.ref.WeakReference

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
            LogManager.log(
                "OUTPUT",
                "Registered HTTP output: ${httpConfig.name} -> ${httpConfig.url}"
            )
        }

        // Link-based outputs (MQTT, WebSocket, TCP)
        config.outputs.link.forEach { linkConfig ->
            val output = createLinkOutput(linkConfig)
            outputs[linkConfig.name] = output
            LogManager.log("OUTPUT", "Registered ${linkConfig.role} output: ${linkConfig.name}")
        }

        // Internal outputs
        config.outputs.internal.forEach { internalConfig ->
            val output = createInternalOutput(internalConfig)
            outputs[internalConfig.name] = output
            LogManager.log(
                "OUTPUT",
                "Registered internal output: ${internalConfig.name} (${internalConfig.type})"
            )
        }
    }

    fun getOutput(name: String): Output? = outputs[name]

    fun getHttpOutput(name: String): HttpOutput? = outputs[name] as? HttpOutput

    fun getInternalOutput(name: String): InternalOutput? = outputs[name] as? InternalOutput

    fun getClipboardOutput(name: String): ClipboardOutput? = outputs[name] as? ClipboardOutput

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
        return when {
            config.linkId.contains("mqtt", ignoreCase = true) -> MqttOutput(config.name, config)
            config.linkId.contains("ws", ignoreCase = true) -> WebSocketOutput(config.name, config)
            else -> TcpOutput(config.name, config)
        }
    }

    private fun createInternalOutput(config: InternalOutputConfig): InternalOutput {
        val ctx = contextRef?.get() ?: throw IllegalStateException("OutputManager not initialized")
        return when (config.type) {
            InternalOutputType.clipboard -> ClipboardOutput(ctx, config.name, config)
            InternalOutputType.file -> FileOutput(ctx, config.name, config)
            InternalOutputType.broadcast -> BroadcastOutput(ctx, config.name, config)
            InternalOutputType.notify -> NotifyOutput(ctx, config.name, config)
        }
    }
}
