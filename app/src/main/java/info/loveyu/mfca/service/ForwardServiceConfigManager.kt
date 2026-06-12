package info.loveyu.mfca.service

import info.loveyu.mfca.config.models.AppConfig
import info.loveyu.mfca.config.ConfigLoader
import info.loveyu.mfca.deadletter.DeadLetterHandler
import info.loveyu.mfca.input.InputManager
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.pipeline.core.RuleEngine
import info.loveyu.mfca.queue.QueueManager
import info.loveyu.mfca.server.HttpServer
import info.loveyu.mfca.server.MessageForwarder
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.Preferences
import info.loveyu.mfca.m2m.core.M2mManager
import java.util.concurrent.ScheduledExecutorService

internal class ForwardServiceConfigManager(
    private val service: ForwardService,
    private val tickController: ForwardServiceTickController,
    private val lockController: ForwardServiceLockController,
    private val appScheduler: ScheduledExecutorService
) {
    private var isApplyingConfig = false
    private var httpServer: HttpServer? = null
    private var legacyMode = false
    private lateinit var preferences: Preferences

    fun init(prefs: Preferences) {
        preferences = prefs
    }

    fun applyConfig(config: AppConfig) {
        if (isApplyingConfig) {
            LogManager.logDebug("CONFIG", "Config application already in progress, skipping duplicate")
            return
        }
        isApplyingConfig = true
        ForwardService.isStarting = true
        ForwardService.onStatsChanged?.invoke()

        appScheduler.execute {
            try {
                applyConfigInternal(config)
            } finally {
                isApplyingConfig = false
                ForwardService.isStarting = false
                ForwardService.onStatsChanged?.invoke()
            }
        }
    }

    private fun applyConfigInternal(config: AppConfig) {
        LogManager.logInfo("CONFIG", "Applying new configuration...")

        service.stopAll()

        ForwardService.currentConfig = config
        legacyMode = false
        M2mManager.initialize(service, config.inputs.m2m, config.plugin.m2mCore, config.plugin.downloadProxy)

        try {
            LogManager.logDebug("CONFIG", "Initializing links...")
            LinkManager.setContext(service)
            LinkManager.initialize(config)

            LogManager.logDebug("CONFIG", "Initializing queues...")
            QueueManager.initialize(service, config)

            LogManager.logDebug("CONFIG", "Initializing outputs...")
            OutputManager.initialize(service, config)

            val ruleEngine = RuleEngine(config, service) {
                ForwardService.forwardedCount++
                ForwardService.onStatsChanged?.invoke()
            }
            service.ruleEngineRef = ruleEngine
            DeadLetterHandler.initialize(service, config.deadLetter) { msg ->
                service.ruleEngineRef?.processDeadLetter(msg)
            }

            InputManager.setContext(service)
            InputManager.initialize(config) { message ->
                service.handleMessage(message)
            }

            LinkManager.connectAll()
            QueueManager.startAll()
            InputManager.startAll()

            tickController.updateConfigIntervals(
                normalIntervalMs = config.scheduler.effectiveTickInterval.millis,
                chargingIntervalMs = config.scheduler.effectiveChargingTickInterval.millis
            )
            service.wakeLockTimeoutMs = config.scheduler.wakeLockTimeout.millis
            service.wifiLockTimeoutMs = config.scheduler.wifiLockTimeout.millis

            ForwardService.isRunning = true
            service.resetTickCount()
            ForwardService.refreshStats()
            service.saveStatus()
            acquireLocks()
            LogManager.logInfo("CONFIG", "Configuration applied successfully. Service started.")
            service.updateNotification()
        } catch (e: Exception) {
            LogManager.logError("CONFIG", "Failed to apply config: ${e.message}")
            e.printStackTrace()
            ForwardService.isRunning = false
            ForwardService.onStartFailed?.invoke("启动失败: ${e.message}")
        }
    }

    fun startWithStoredConfig(): Boolean {
        val savedConfig = preferences.loadFullConfig()
        if (savedConfig != null && savedConfig.isNotBlank()) {
            try {
                val config = ConfigLoader.loadConfig(savedConfig)
                applyConfig(config)
                return true
            } catch (e: Exception) {
                LogManager.logWarn("CONFIG", "Failed to load saved config: ${e.message}")
            }
        }
        ForwardService.isRunning = false
        LogManager.logInfo("SERVICE", "No valid config found, service not started")
        return false
    }

    fun startLegacyMode() {
        if (legacyMode) return
        startLegacyModeInternal()
    }

    private fun startLegacyModeInternal() {
        val port = preferences.port
        httpServer = HttpServer(port) { body ->
            if (!ForwardService.isReceivingEnabled) return@HttpServer

            ForwardService.receivedCount++
            ForwardService.onStatsChanged?.invoke()

            val target = preferences.forwardTarget
            if (target.isNotEmpty() && ForwardService.isForwardingEnabled) {
                MessageForwarder.forward(target, body) { success ->
                    if (success) {
                        ForwardService.forwardedCount++
                        ForwardService.onStatsChanged?.invoke()
                    }
                }
            }
        }
        httpServer?.startServer()
        ForwardService.isRunning = true
        service.saveStatus()
        acquireLocks()
        LogManager.logInfo("SERVICE", "Legacy mode started on port $port")
    }

    fun stopLegacyMode() {
        httpServer?.stopServer()
        httpServer = null
        ForwardService.isRunning = false
        ForwardService.receivedCount = 0
        ForwardService.forwardedCount = 0
    }

    private fun acquireLocks() {
        lockController.acquireLocks(
            wakeEnabled = ForwardService.isWakeLockEnabled,
            wifiEnabled = ForwardService.isWifiLockEnabled,
            wakeTimeoutMs = service.wakeLockTimeoutMs,
            wifiTimeoutMs = service.wifiLockTimeoutMs,
            onWakeAutoRelease = { service.onWakeLockAutoReleased() },
            onWifiAutoRelease = { service.onWifiLockAutoReleased() }
        )
    }

    fun stop() {
        stopLegacyMode()
    }
}
