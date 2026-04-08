package info.loveyu.mfca.input

import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.util.LogManager

/**
 * 输入源管理器
 */
object InputManager {

    private val inputs = mutableMapOf<String, InputSource>()
    private var globalMessageListener: ((InputMessage) -> Unit)? = null

    fun initialize(config: AppConfig, messageHandler: (InputMessage) -> Unit) {
        clear()
        globalMessageListener = messageHandler

        // HTTP inputs
        config.inputs.http.forEach { httpConfig ->
            val input = HttpInput(httpConfig)
            inputs[httpConfig.name] = input
            input.setOnMessageListener { msg -> messageHandler(msg) }
            LogManager.appendLog("INPUT", "Registered HTTP input: ${httpConfig.name} on ${httpConfig.listen}:${httpConfig.port}${httpConfig.path}")
        }

        // Link-based inputs (MQTT, WebSocket, TCP)
        config.inputs.link.forEach { linkConfig ->
            val input = createLinkInput(linkConfig)
            inputs[linkConfig.name] = input
            input.setOnMessageListener { msg -> messageHandler(msg) }
            LogManager.appendLog("INPUT", "Registered ${linkConfig.role} input: ${linkConfig.name} (link: ${linkConfig.linkId})")
        }
    }

    fun startAll() {
        inputs.values.forEach { input ->
            try {
                if (!input.isRunning()) {
                    input.start()
                }
            } catch (e: Exception) {
                LogManager.appendLog("INPUT", "Failed to start ${input.inputName}: ${e.message}")
            }
        }
    }

    fun stopAll() {
        inputs.values.forEach { input ->
            try {
                input.stop()
            } catch (e: Exception) {
                LogManager.appendLog("INPUT", "Error stopping ${input.inputName}: ${e.message}")
            }
        }
    }

    fun clear() {
        stopAll()
        inputs.clear()
        globalMessageListener = null
    }

    fun getInput(name: String): InputSource? = inputs[name]

    fun getAllInputs(): Map<String, InputSource> = inputs.toMap()

    fun getInputState(name: String): Boolean = inputs[name]?.isRunning() ?: false

    private fun createLinkInput(config: info.loveyu.mfca.config.LinkInputConfig): InputSource {
        return when {
            config.linkId.contains("mqtt", ignoreCase = true) -> MqttInput(config)
            config.linkId.contains("ws", ignoreCase = true) -> WebSocketInput(config)
            else -> TcpInput(config)
        }
    }
}
