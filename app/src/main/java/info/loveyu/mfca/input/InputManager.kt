package info.loveyu.mfca.input

import android.content.Context
import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.config.HttpInputConfig
import info.loveyu.mfca.config.LinkInputConfig
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.NetworkChecker
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 输入源管理器 - Service 常驻架构
 *
 * 特性:
 * - 定时检查输入源健康状态
 * - 自动重启断开的输入源
 * - 支持链接状态联动
 */
object InputManager {

    private val inputs = mutableMapOf<String, InputSource>()
    private val configs = mutableMapOf<String, InputSourceConfig>()
    private var globalMessageListener: ((InputMessage) -> Unit)? = null
    private var applicationContext: Context? = null

    // Service-resident executor
    private val inputScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var healthCheckFuture: ScheduledFuture<*>? = null

    /**
     * 输入源配置信息
     */
    private data class InputSourceConfig(
        val name: String,
        val isLinkBased: Boolean = false,
        val linkId: String? = null,
        val whenCondition: String? = null,
        val deny: String? = null
    )

    fun setContext(context: Context) {
        applicationContext = context.applicationContext
    }

    fun initialize(config: AppConfig, messageHandler: (InputMessage) -> Unit) {
        clear()
        globalMessageListener = messageHandler

        // HTTP inputs
        config.inputs.http.forEach { httpConfig ->
            val input = HttpInput(httpConfig)
            inputs[httpConfig.name] = input
            configs[httpConfig.name] = InputSourceConfig(
                name = httpConfig.name,
                isLinkBased = false,
                linkId = null,
                whenCondition = httpConfig.whenCondition,
                deny = httpConfig.deny
            )
            input.setOnMessageListener { msg -> messageHandler(msg) }
            LogManager.appendLog("INPUT", "Registered HTTP input: ${httpConfig.name} dsn=${httpConfig.dsn}")
        }

        // Link-based inputs (MQTT, WebSocket, TCP)
        config.inputs.link.forEach { linkConfig ->
            val input = createLinkInput(linkConfig)
            inputs[linkConfig.name] = input
            configs[linkConfig.name] = InputSourceConfig(
                name = linkConfig.name,
                isLinkBased = true,
                linkId = linkConfig.linkId,
                whenCondition = linkConfig.whenCondition,
                deny = linkConfig.deny
            )
            input.setOnMessageListener { msg -> messageHandler(msg) }
            LogManager.appendLog("INPUT", "Registered ${linkConfig.role} input: ${linkConfig.name} (link: ${linkConfig.linkId})")
        }

        // Start health check
        startHealthCheck()
    }

    /**
     * 启动输入源健康检查 (每30秒)
     */
    fun startHealthCheck() {
        healthCheckFuture?.cancel(false)
        healthCheckFuture = inputScheduler.scheduleAtFixedRate({
            checkInputHealth()
        }, 30, 30, TimeUnit.SECONDS)
    }

    /**
     * 停止输入源健康检查
     */
    fun stopHealthCheck() {
        healthCheckFuture?.cancel(false)
        healthCheckFuture = null
    }

    /**
     * 检查输入源健康状态，自动重启断开的输入源
     */
    private fun checkInputHealth() {
        if (!LinkManager.isNetworkAvailable()) return

        checkAllInputConditions()
    }

    /**
     * 检查所有输入源的网络条件，不符合的停止，符合但停止的重新启动
     */
    fun checkAllInputConditions() {
        val ctx = applicationContext ?: return
        if (!LinkManager.isNetworkAvailable()) return

        inputs.values.forEach { input ->
            val config = configs[input.inputName] ?: return@forEach

            // Check network conditions (when/deny)
            if (!NetworkChecker.shouldEnable(ctx, config.whenCondition, config.deny)) {
                if (input.isRunning()) {
                    LogManager.appendLog("INPUT", "Stopping ${input.inputName}: network conditions not met")
                    input.stop()
                }
                return@forEach
            }

            // For link-based inputs, check if the associated link is connected
            if (config.isLinkBased && config.linkId != null) {
                val link = LinkManager.getLink(config.linkId)
                if (link == null || !link.isConnected()) {
                    if (input.isRunning()) {
                        LogManager.appendLog("INPUT", "Stopping ${input.inputName}: link ${config.linkId} not connected")
                        input.stop()
                    }
                    return@forEach
                }
            }

            // Try to restart if not running (skip inputs with permanent errors)
            if (!input.isRunning()) {
                // Skip inputs with permanent errors (e.g. port conflict, DSN parse failure)
                if (input.getError() != null) {
                    return@forEach
                }
                LogManager.appendLog("INPUT", "Restarting ${input.inputName}...")
                try {
                    input.start()
                } catch (e: Exception) {
                    LogManager.appendLog("INPUT", "Restart failed for ${input.inputName}: ${e.message}")
                }
            }
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
        stopHealthCheck()
        stopAll()
        inputs.clear()
        configs.clear()
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
