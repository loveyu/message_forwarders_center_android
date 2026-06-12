package info.loveyu.mfca.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import info.loveyu.mfca.ui.main.MainActivity
import info.loveyu.mfca.config.models.AppConfig
import info.loveyu.mfca.pipeline.core.RuleEngine
import info.loveyu.mfca.deadletter.DeadLetterHandler
import info.loveyu.mfca.input.InputManager
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.queue.QueueManager
import info.loveyu.mfca.receiver.ServiceWatchdogJob
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.Preferences
import info.loveyu.mfca.m2m.core.M2mManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class ForwardService : Service() {

    companion object {
        const val CHANNEL_ID = "forward_service_status_channel_v2"
        const val LINK_ERROR_CHANNEL_ID = "link_error_channel"
        const val LINK_ERROR_GROUP_ID = "link_error_group"
        const val NOTIFICATION_ID = 1
        const val ACTION_INIT = "info.loveyu.mfca.action.INIT"
        const val ACTION_START = "info.loveyu.mfca.action.START"
        const val ACTION_STOP = "info.loveyu.mfca.action.STOP"
        const val ACTION_TOGGLE_RECEIVE = "info.loveyu.mfca.action.TOGGLE_RECEIVE"
        const val ACTION_TOGGLE_FORWARD = "info.loveyu.mfca.action.TOGGLE_FORWARD"
        const val ACTION_RELOAD_CONFIG = "info.loveyu.mfca.action.RELOAD_CONFIG"
        const val ACTION_TOGGLE_WAKELOCK = "info.loveyu.mfca.action.TOGGLE_WAKELOCK"
        const val ACTION_TOGGLE_WIFILOCK = "info.loveyu.mfca.action.TOGGLE_WIFILOCK"

        /**
         * 由外部事件触发一次提前 tick（网络变更、前后台切换等）。
         * 仅当距上次 tick 超过最小间隔时才执行，执行后重置周期定时器。
         */
        fun triggerTick() {
            serviceInstance?.doTriggerTick()
        }

        @Volatile
        var isRunning = false
            internal set

        @Volatile
        var isStarting = false
            internal set

        @Volatile
        var receivedCount = 0
            internal set

        @Volatile
        var forwardedCount = 0
            internal set

        @Volatile
        var isReceivingEnabled = true
            internal set

        @Volatile
        var isForwardingEnabled = true
            internal set

        @Volatile
        var isWakeLockEnabled = false
            internal set

        @Volatile
        var isWifiLockEnabled = false
            internal set

        // 简写首字母统计
        @Volatile
        var linkCount = 0
            internal set

        @Volatile
        var inputCount = 0
            internal set

        @Volatile
        var outputCount = 0
            internal set

        var onStatsChanged: (() -> Unit)? = null
        var onStartFailed: ((String) -> Unit)? = null

        var serviceInstance: ForwardService? = null
            internal set

        // Current loaded config
        @Volatile
        var currentConfig: AppConfig? = null
            internal set

        // Current config URL
        @Volatile
        var currentConfigUrl: String = ""

        fun isServiceAlive(): Boolean = serviceInstance != null

        fun refreshNotification() {
            serviceInstance?.updateNotification()
        }

        fun clearIconCaches() {
            serviceInstance?.ruleEngineRef?.clearEnricherCaches()
        }
    }

    internal lateinit var preferences: Preferences

    // New architecture components
    @Volatile
    var ruleEngineRef: RuleEngine? = null

    // 统一调度器：替代原来分散的 statsScheduler + configExecutor
    // 同时处理周期性 tick 和一次性 config 加载任务
    private val appScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val tickController by lazy { ForwardServiceTickController(this, appScheduler) }
    private val configManager by lazy { ForwardServiceConfigManager(this, tickController, lockController, appScheduler) }

    // Lock timeout from config (0 = permanent)
    @Volatile
    var wakeLockTimeoutMs: Long = 3_600_000L // default 1h
    @Volatile
    var wifiLockTimeoutMs: Long = 3_600_000L // default 1h

    @Volatile
    private var destroyReason = "unknown"
    private val notificationDelegate by lazy { ForwardServiceNotificationDelegate(this) }
    private val lockController by lazy { ForwardServiceLockController(this, appScheduler) }
    private val screenEventController by lazy { ForwardServiceScreenEventController(this, appScheduler) }

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        preferences = Preferences(this)
        configManager.init(preferences)
        createNotificationChannel()
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            LogManager.logWarn("SERVICE", "startForeground failed, stopping self: ${e.message}")
            serviceInstance = null
            stopSelf()
            return
        }
        tickController.start()
        registerScreenEvents()
        scheduleWatchdogJob()
    }

    // Ticker methods delegated to tickController
    private fun startTick() = tickController.start()
    fun doTriggerTick() = tickController.doTriggerTick()
    internal fun invalidateNotificationStatsCache() = notificationDelegate.invalidateStatsCache()

    /**
     * 动态注册系统事件广播接收器。
     * ACTION_SCREEN_ON 是受保护广播，只能动态注册。
     * 同时监听充放电事件以动态调整 tick 间隔。
     */
    private fun registerScreenEvents() {
        screenEventController.register(
            onInitialChargingDetected = { charging ->
                tickController.isCharging = charging
                tickController.tickIntervalMs = if (charging) tickController.chargingTickIntervalMs else tickController.normalTickIntervalMs
                LogManager.logDebug("SERVICE", "Initial charging state: ${tickController.isCharging}, tickInterval=${tickController.tickIntervalMs}ms")
            },
            onChargingChanged = { charging -> tickController.updateChargingState(charging) },
            onTriggerTick = { tickController.doTriggerTick() },
            onNotificationCheck = { reason -> checkNotificationPresenceNow(reason) }
        )
    }

    // Charging state update delegated to tickController

    private fun unregisterScreenEvents() {
        screenEventController.unregister()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                destroyReason = "user_stop"
                cancelWatchdogJob()
                stopAll()
                saveStatus()
                updateNotification()
                onStatsChanged?.invoke()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_RECEIVE -> {
                isReceivingEnabled = !isReceivingEnabled
                preferences.receivingEnabled = isReceivingEnabled
                saveStatus()
                notificationDelegate.invalidateStatsCache()
                updateNotification()
                onStatsChanged?.invoke()
                LogManager.logInfo("SERVICE", if (isReceivingEnabled) "已恢复接收" else "已暂停接收")
                return START_STICKY
            }
            ACTION_TOGGLE_FORWARD -> {
                isForwardingEnabled = !isForwardingEnabled
                preferences.forwardingEnabled = isForwardingEnabled
                saveStatus()
                notificationDelegate.invalidateStatsCache()
                updateNotification()
                onStatsChanged?.invoke()
                LogManager.logInfo("SERVICE", if (isForwardingEnabled) "已恢复转发" else "已暂停转发")
                return START_STICKY
            }
            ACTION_TOGGLE_WAKELOCK -> {
                isWakeLockEnabled = !isWakeLockEnabled
                if (isWakeLockEnabled) {
                    acquireWakeLock()
                } else {
                    releaseWakeLock()
                }
                saveStatus()
                notificationDelegate.invalidateStatsCache()
                updateNotification()
                onStatsChanged?.invoke()
                LogManager.logInfo("SERVICE", if (isWakeLockEnabled) "已启用 WakeLock" else "已关闭 WakeLock")
                return START_STICKY
            }
            ACTION_TOGGLE_WIFILOCK -> {
                isWifiLockEnabled = !isWifiLockEnabled
                if (isWifiLockEnabled) {
                    acquireWifiLock()
                } else {
                    releaseWifiLock()
                }
                saveStatus()
                notificationDelegate.invalidateStatsCache()
                updateNotification()
                onStatsChanged?.invoke()
                LogManager.logInfo("SERVICE", if (isWifiLockEnabled) "已启用 WifiLock" else "已关闭 WifiLock")
                return START_STICKY
            }
            ACTION_RELOAD_CONFIG -> {
                stopAll()
                configManager.startWithStoredConfig()
                checkNotificationPresenceNow("reload_config")
                return START_STICKY
            }
            "OPEN_NOTIFICATION" -> {
                val outputName = intent.getStringExtra("output_name") ?: ""
                val notificationTag = intent.getStringExtra("notification_tag") ?: ""
                val notificationId = intent.getIntExtra("notification_id", -1)
                LogManager.logInfo("INTERNAL", "Notification opened: output=$outputName, tag=$notificationTag, id=$notificationId")
                // 启动通知历史页面并定位到该通知
                val activityIntent = Intent(this@ForwardService, MainActivity::class.java)
                activityIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                activityIntent.putExtra("notify_id", notificationId)
                activityIntent.putExtra("highlight", true)
                startActivity(activityIntent)
                return START_STICKY
            }
        }

        // Load status from YAML config
        loadStatus()

        // startForeground 已在 onCreate 中调用，此处根据加载的状态刷新通知
        updateNotification()

        if (intent?.action == ACTION_START) {
            configManager.startWithStoredConfig()
        } else if (intent?.action == null && wasRunningBeforeRestart) {
            // Service restarted by system (START_STICKY) after being killed
            // Auto-restore previously running service
            LogManager.logInfo("SERVICE", "Auto-restoring service after system restart")
            wasRunningBeforeRestart = false
            configManager.startWithStoredConfig()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isRunning) {
            destroyReason = "task_removed"
            LogManager.logInfo("SERVICE", "Task removed, restarting service to keep running")
            val restartIntent = Intent(this, ForwardService::class.java).apply {
                action = ACTION_START
            }
            startForegroundService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val wasRunning = isRunning
        val finalDestroyReason = when {
            destroyReason != "unknown" -> destroyReason
            wasRunning -> "destroyed_while_running"
            else -> "destroyed_after_stop"
        }
        LogManager.logWarn(
            "SERVICE",
            "onDestroy invoked, reason=$finalDestroyReason, running=$wasRunning"
        )
        LogManager.writeExitEventSync(
            ctx = this,
            event = "forward-service-onDestroy",
            reason = finalDestroyReason,
            extras = mapOf(
                "wasRunning" to wasRunning.toString(),
                "receivedCount" to receivedCount.toString(),
                "forwardedCount" to forwardedCount.toString(),
                "linkCount" to linkCount.toString(),
                "inputCount" to inputCount.toString(),
                "outputCount" to outputCount.toString(),
                "isReceivingEnabled" to isReceivingEnabled.toString(),
                "isForwardingEnabled" to isForwardingEnabled.toString(),
                "isWakeLockEnabled" to isWakeLockEnabled.toString(),
                "isWifiLockEnabled" to isWifiLockEnabled.toString(),
                "currentConfigUrl" to currentConfigUrl.ifBlank { "<empty>" }
            )
        )
        LogManager.flushAndSync()
        stopForeground(STOP_FOREGROUND_REMOVE)
        tickController.stop()
        appScheduler.shutdown()
        unregisterScreenEvents()
        serviceInstance = null
        stopAll()
        super.onDestroy()
        // 自重启：仅在被系统强杀时尝试，用户主动停止时 wasRunning=false 跳过
        if (wasRunning) {
            try {
                startForegroundService(Intent(this, ForwardService::class.java))
                LogManager.logInfo("SERVICE", "Self-restart triggered in onDestroy")
                LogManager.flushAndSync()
            } catch (e: Exception) {
                LogManager.logWarn("SERVICE", "Self-restart in onDestroy failed: ${e.message}")
                LogManager.writeExitEventSync(
                    ctx = this,
                    event = "forward-service-restart-failed",
                    reason = e.message ?: "unknown",
                    throwable = e,
                    extras = mapOf("destroyReason" to finalDestroyReason)
                )
            }
        }
    }

    private fun startLegacyMode() {
        configManager.startLegacyMode()
    }

    fun stopLegacyMode() {
        configManager.stopLegacyMode()
    }

    fun applyConfig(config: AppConfig) {
        configManager.applyConfig(config)
    }

    fun startWithStoredConfig(): Boolean {
        return configManager.startWithStoredConfig()
    }

    fun resetTickCount() {
        tickController.tickCount = 0
    }



    internal fun stopAll() {
        InputManager.stopAll()
        QueueManager.stopAll()
        LinkManager.disconnectAll()
        OutputManager.clear()
        M2mManager.clear()
        releaseLocks()
        tickController.cancelEarlyTick()
        isRunning = false
        receivedCount = 0
        forwardedCount = 0
        ruleEngineRef?.shutdown()
        ruleEngineRef = null
        DeadLetterHandler.clear()
        LogManager.logInfo("SERVICE", "All components stopped")
    }

    internal fun onWakeLockAutoReleased() {
        isWakeLockEnabled = false
        saveStatus()
        notificationDelegate.invalidateStatsCache()
        updateNotification()
        onStatsChanged?.invoke()
        LogManager.logInfo("SERVICE", "WakeLock auto-released after ${wakeLockTimeoutMs / 1000}s timeout")
    }

    internal fun onWifiLockAutoReleased() {
        isWifiLockEnabled = false
        saveStatus()
        notificationDelegate.invalidateStatsCache()
        updateNotification()
        onStatsChanged?.invoke()
        LogManager.logInfo("SERVICE", "WifiLock auto-released after ${wifiLockTimeoutMs / 1000}s timeout")
    }

    /**
     * 注册 JobScheduler 看门狗（15 分钟触发一次 [ServiceWatchdogJob]）。
     * JobService 属于 Android 12+ 明确豁免的上下文，允许启动前台服务。
     * 仅在用户主动停止服务（ACTION_STOP）时取消，崩溃后仍能自动重启。
     */
    private fun scheduleWatchdogJob() {
        ServiceWatchdogJob.schedule(this)
    }

    private fun cancelWatchdogJob() {
        ServiceWatchdogJob.cancel(this)
    }

    @Volatile
    internal var wasRunningBeforeRestart = false

    private fun createNotificationChannel() {
        notificationDelegate.createNotificationChannels()
    }

    private fun createNotification(): Notification {
        return notificationDelegate.createNotification()
    }

    fun updateNotification() {
        notificationDelegate.updateNotification()
    }

    fun checkNotificationPresenceNow(reason: String) {
        tickController.lastNotificationPresenceCheckMs = System.currentTimeMillis()
        LogManager.logDebug("SERVICE", "Checking foreground notification presence: $reason")
        updateNotification()
    }

    private fun acquireLocks() {
        lockController.acquireLocks(
            wakeEnabled = isWakeLockEnabled,
            wifiEnabled = isWifiLockEnabled,
            wakeTimeoutMs = wakeLockTimeoutMs,
            wifiTimeoutMs = wifiLockTimeoutMs,
            onWakeAutoRelease = { onWakeLockAutoReleased() },
            onWifiAutoRelease = { onWifiLockAutoReleased() }
        )
    }

    private fun acquireWakeLock() {
        lockController.acquireWakeLock(wakeLockTimeoutMs) {
            onWakeLockAutoReleased()
        }
    }

    private fun acquireWifiLock() {
        lockController.acquireWifiLock(wifiLockTimeoutMs) {
            onWifiLockAutoReleased()
        }
    }

    private fun releaseWifiLock() {
        lockController.releaseWifiLock()
    }

    private fun releaseWakeLock() {
        lockController.releaseWakeLock()
    }

    private fun releaseLocks() {
        lockController.releaseAll()
    }

}
