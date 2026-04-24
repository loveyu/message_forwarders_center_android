package info.loveyu.mfca.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import info.loveyu.mfca.MainActivity
import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.config.AppStatusConfig
import info.loveyu.mfca.config.ConfigLoader
import info.loveyu.mfca.deadletter.DeadLetterHandler
import info.loveyu.mfca.input.InputManager
import info.loveyu.mfca.input.InputMessage
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.pipeline.RuleEngine
import info.loveyu.mfca.queue.QueueManager
import info.loveyu.mfca.receiver.ServiceWatchdogJob
import info.loveyu.mfca.server.HttpServer
import info.loveyu.mfca.server.MessageForwarder
import info.loveyu.mfca.util.AppStatusManager
import info.loveyu.mfca.util.LogLevel
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.NetworkChecker
import info.loveyu.mfca.util.Preferences
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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

        /** 失败计数重置间隔：每 20 个 tick（默认 tick=40s 时约 13 分钟） */
        private const val FAILURE_RESET_TICK_INTERVAL = 20

        /** 事件触发 tick 的最小间隔：5 秒，防止事件风暴 */
        private const val MIN_TICK_INTERVAL_MS = 5_000L
        private const val NOTIFICATION_PRESENCE_CHECK_INTERVAL_MS = 10 * 60 * 1000L

        /**
         * 由外部事件触发一次提前 tick（网络变更、前后台切换等）。
         * 仅当距上次 tick 超过最小间隔时才执行，执行后重置周期定时器。
         */
        fun triggerTick() {
            serviceInstance?.doTriggerTick()
        }

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isStarting = false
            private set

        @Volatile
        var receivedCount = 0
            private set

        @Volatile
        var forwardedCount = 0
            private set

        @Volatile
        var isReceivingEnabled = true
            private set

        @Volatile
        var isForwardingEnabled = true
            private set

        @Volatile
        var isWakeLockEnabled = false
            private set

        @Volatile
        var isWifiLockEnabled = false
            private set

        // 简写首字母统计
        @Volatile
        var linkCount = 0
            private set

        @Volatile
        var inputCount = 0
            private set

        @Volatile
        var outputCount = 0
            private set

        var onStatsChanged: (() -> Unit)? = null
        var onStartFailed: ((String) -> Unit)? = null

        private var serviceInstance: ForwardService? = null

        // Current loaded config
        @Volatile
        var currentConfig: AppConfig? = null
            private set

        // Current config URL
        @Volatile
        var currentConfigUrl: String = ""

        fun isServiceAlive(): Boolean = serviceInstance != null

        fun refreshNotification() {
            serviceInstance?.updateNotification()
        }

        fun refreshStats() {
            // Only count enabled components based on whenCondition/deny
            val config = currentConfig
            if (config != null) {
                val ctx = serviceInstance ?: return
                linkCount = config.links.count { link ->
                    NetworkChecker.shouldEnable(ctx, link.whenCondition, link.deny)
                }
                inputCount = config.inputs.http.count { input ->
                    NetworkChecker.shouldEnable(ctx, input.whenCondition, input.deny)
                } + config.inputs.link.count { input ->
                    NetworkChecker.shouldEnable(ctx, input.whenCondition, input.deny)
                }
                // HTTP and Internal outputs don't have whenCondition/deny, so always enabled
                // Only Link outputs have whenCondition/deny
                outputCount = config.outputs.http.size + config.outputs.internal.size +
                    config.outputs.link.count { output ->
                        NetworkChecker.shouldEnable(ctx, output.whenCondition, output.deny)
                    }
            } else {
                // Fallback to all components if config not loaded yet
                linkCount = LinkManager.getAllLinks().size
                inputCount = InputManager.getAllInputs().size
                outputCount = OutputManager.getAllOutputs().size
            }
            onStatsChanged?.invoke()
            serviceInstance?.updateNotification()
        }

        fun loadConfig(yamlContent: String, configUrl: String = ""): Boolean {
            return try {
                val config = ConfigLoader.loadConfig(yamlContent)
                currentConfigUrl = configUrl
                serviceInstance?.applyConfig(config)
                true
            } catch (e: Exception) {
                LogManager.logWarn("CONFIG", "Failed to load config: ${e.message}")
                false
            }
        }

        fun updateStatus(configUrl: String = currentConfigUrl) {
            serviceInstance?.saveStatus()
        }

        fun clearIconCaches() {
            serviceInstance?.ruleEngine?.clearEnricherCaches()
        }
    }

    private var httpServer: HttpServer? = null
    private var legacyMode = false
    private lateinit var preferences: Preferences

    // New architecture components
    private var ruleEngine: RuleEngine? = null
    private var deadLetterHandler: DeadLetterHandler? = null

    // 统一调度器：替代原来分散的 statsScheduler + configExecutor
    // 同时处理周期性 tick 和一次性 config 加载任务
    private val appScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val tickScheduler = CoalescingTicker(
        scheduler = appScheduler,
        onTick = { onTick() },
        onError = { e -> LogManager.logError("SERVICE", "Tick execution error: ${e.message}") }
    )
    private var earlyTickFuture: ScheduledFuture<*>? = null
    private var tickCount = 0

    // tick 间隔，从 config.scheduler 读取，默认 40s
    @Volatile
    private var tickIntervalMs: Long = 40_000L

    // 上次 tick 执行时间，用于事件触发时的最小间隔判断
    @Volatile
    private var lastTickTime: Long = 0L
    @Volatile
    private var lastNotificationPresenceCheckMs: Long = 0L

    // 充电状态：影响 tick 间隔
    @Volatile
    private var isCharging = false

    // 配置的两个间隔值
    @Volatile
    private var normalTickIntervalMs: Long = 40_000L
    @Volatile
    private var chargingTickIntervalMs: Long = 40_000L

    // Lock timeout from config (0 = permanent)
    @Volatile
    private var wakeLockTimeoutMs: Long = 3_600_000L // default 1h
    @Volatile
    private var wifiLockTimeoutMs: Long = 3_600_000L // default 1h

    @Volatile
    private var isApplyingConfig = false
    private val notificationDelegate by lazy { ForwardServiceNotificationDelegate(this) }
    private val lockController by lazy { ForwardServiceLockController(this, appScheduler) }
    private val screenEventController by lazy { ForwardServiceScreenEventController(this, appScheduler) }

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        preferences = Preferences(this)
        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startTick()
        registerScreenEvents()
        scheduleWatchdogJob()
    }

    /**
     * 启动统一 Ticker
     */
    private fun startTick() {
        cancelEarlyTick()
        tickScheduler.start(tickIntervalMs)
    }

    /**
     * 统一 Ticker 回调：所有定时检查集中执行
     */
    private fun onTick() {
        cancelEarlyTick()
        if (!isRunning) return
        val now = System.currentTimeMillis()
        lastTickTime = now
        tickCount++
        LogManager.logDebug("SERVICE", "Tick #$tickCount start")

        // 1. Link 健康检查 + MQTT 心跳
        val nextLinkTickDelayMs = LinkManager.onTick()

        // 2. Input 健康检查
        InputManager.onTick()

        // 3. SqliteQueue 处理（由 tick 触发，异步执行到期批次）
        QueueManager.onTick()

        // 4. 每 N 个 tick 执行失败重置（≈10 分钟）
        if (tickCount % FAILURE_RESET_TICK_INTERVAL == 0) {
            LinkManager.onFailureResetTick()
        }

        // 5. 通知历史自动清理（暂时关闭，后续调整）
        // if (tickCount % FAILURE_RESET_TICK_INTERVAL == 0) {
        //     NotifyHistoryCleanup.onTick(this)
        // }


        // 6. 每 10 分钟兜底检查通知是否仍在，并强制刷新使其保持在通知栏顶部附近
        if (now - lastNotificationPresenceCheckMs >= NOTIFICATION_PRESENCE_CHECK_INTERVAL_MS) {
            checkNotificationPresenceNow("periodic_10m")
            notificationDelegate.invalidateStatsCache()
            updateNotification()
        }

        // 7. 批量 flush 日志文件缓冲
        LogManager.logDebug("SERVICE", "Tick #$tickCount end, flushing logs")
        LogManager.flush()

        // 8. 批量 flush 文件输出缓冲
        OutputManager.flushAllFileOutputs()

        scheduleEarlyTick(nextLinkTickDelayMs)
    }

    /**
     * 由外部事件触发一次提前 tick。
     * 检查最小触发间隔，满足则请求执行一次 tick（与周期 tick 合并调度）。
     */
    private fun doTriggerTick() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTickTime
        if (elapsed < MIN_TICK_INTERVAL_MS) {
            LogManager.logDebug("SERVICE", "TriggerTick debounced: ${elapsed}ms < ${MIN_TICK_INTERVAL_MS}ms")
            return
        }
        LogManager.logDebug("SERVICE", "TriggerTick: event-driven early tick (last was ${elapsed}ms ago)")
        tickScheduler.request()
    }

    private fun scheduleEarlyTick(delayMs: Long?) {
        cancelEarlyTick()
        if (!isRunning || delayMs == null) return
        val boundedDelayMs = delayMs.coerceAtLeast(1L)
        if (boundedDelayMs >= tickIntervalMs) return
        earlyTickFuture = appScheduler.schedule({
            tickScheduler.request()
        }, boundedDelayMs, TimeUnit.MILLISECONDS)
    }

    private fun cancelEarlyTick() {
        earlyTickFuture?.cancel(false)
        earlyTickFuture = null
    }

    /**
     * 动态注册系统事件广播接收器。
     * ACTION_SCREEN_ON 是受保护广播，只能动态注册。
     * 同时监听充放电事件以动态调整 tick 间隔。
     */
    private fun registerScreenEvents() {
        screenEventController.register(
            onInitialChargingDetected = { charging ->
                isCharging = charging
                tickIntervalMs = if (charging) chargingTickIntervalMs else normalTickIntervalMs
                LogManager.logDebug("SERVICE", "Initial charging state: $isCharging, tickInterval=${tickIntervalMs}ms")
            },
            onChargingChanged = { charging -> updateChargingState(charging) },
            onTriggerTick = { doTriggerTick() },
            onNotificationCheck = { reason -> checkNotificationPresenceNow(reason) }
        )
    }

    /**
     * 更新充电状态并动态调整 tick 间隔，同时触发一次 tick。
     */
    private fun updateChargingState(charging: Boolean) {
        val oldInterval = tickIntervalMs
        isCharging = charging
        tickIntervalMs = if (charging) chargingTickIntervalMs else normalTickIntervalMs
        if (tickIntervalMs != oldInterval) {
            LogManager.logDebug("SERVICE", "Tick interval changed: ${oldInterval}ms → ${tickIntervalMs}ms (charging=$charging)")
            // 重启 tick 使用新间隔
            startTick()
        }
        // 充放电状态变化时立即触发一次 tick
        doTriggerTick()
    }

    private fun unregisterScreenEvents() {
        screenEventController.unregister()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
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
                start()
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
            start()
        } else if (intent?.action == null && wasRunningBeforeRestart) {
            // Service restarted by system (START_STICKY) after being killed
            // Auto-restore previously running service
            LogManager.logInfo("SERVICE", "Auto-restoring service after system restart")
            wasRunningBeforeRestart = false
            start()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isRunning) {
            LogManager.logInfo("SERVICE", "Task removed, restarting service to keep running")
            val restartIntent = Intent(this, ForwardService::class.java).apply {
                action = ACTION_START
            }
            startForegroundService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 捕获当前运行状态：系统强杀时 isRunning=true；用户主动停止时 stopAll() 已被 ACTION_STOP 提前调用，isRunning=false
        val wasRunning = isRunning
        stopForeground(STOP_FOREGROUND_REMOVE)
        tickScheduler.stop()
        cancelEarlyTick()
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
            } catch (e: Exception) {
                LogManager.logWarn("SERVICE", "Self-restart in onDestroy failed: ${e.message}")
            }
        }
    }

    private fun start() {
        // Try to load YAML config if available
        val savedConfig = preferences.loadFullConfig()
        if (savedConfig != null && savedConfig.isNotBlank()) {
            try {
                val config = ConfigLoader.loadConfig(savedConfig)
                applyConfig(config)
                return
            } catch (e: Exception) {
                LogManager.logWarn("CONFIG", "Failed to load saved config: ${e.message}")
            }
        }

        // No valid config, just mark as not running
        isRunning = false
        LogManager.logInfo("SERVICE", "No valid config found, service not started")
    }

    private fun startLegacyMode() {
        if (legacyMode) return
        startLegacyModeInternal()
    }

    private fun startLegacyModeInternal() {
        val port = preferences.port
        httpServer = HttpServer(port) { body ->
            if (!isReceivingEnabled) return@HttpServer

            receivedCount++
            onStatsChanged?.invoke()

            val target = preferences.forwardTarget
            if (target.isNotEmpty() && isForwardingEnabled) {
                MessageForwarder.forward(target, body) { success ->
                    if (success) {
                        forwardedCount++
                        onStatsChanged?.invoke()
                    }
                }
            }
        }
        httpServer?.startServer()
        isRunning = true
        saveStatus()
        acquireLocks()
        LogManager.logInfo("SERVICE", "Legacy mode started on port $port")
    }

    private fun stopLegacyMode() {
        httpServer?.stopServer()
        httpServer = null
        isRunning = false
        receivedCount = 0
        forwardedCount = 0
    }

    private fun applyConfig(config: AppConfig) {
        if (isApplyingConfig) {
            LogManager.logDebug("CONFIG", "Config application already in progress, skipping duplicate")
            return
        }
        isApplyingConfig = true
        isStarting = true
        onStatsChanged?.invoke()

        appScheduler.execute {
            try {
                applyConfigInternal(config)
            } finally {
                isApplyingConfig = false
                isStarting = false
                onStatsChanged?.invoke()
            }
        }
    }

    private fun applyConfigInternal(config: AppConfig) {
        LogManager.logInfo("CONFIG", "Applying new configuration...")

        // Stop existing components
        stopAll()

        currentConfig = config
        legacyMode = false

        // Initialize components in order
        try {
            // 1. Initialize Links
            LogManager.logDebug("CONFIG", "Initializing links...")
            LinkManager.setContext(this)
            LinkManager.initialize(config)

            // 2. Initialize Queues
            LogManager.logDebug("CONFIG", "Initializing queues...")
            QueueManager.initialize(this, config)

            // 3. Initialize Outputs
            LogManager.logDebug("CONFIG", "Initializing outputs...")
            OutputManager.initialize(this, config)

            // 4. Initialize Dead Letter Handler
            deadLetterHandler = DeadLetterHandler(this, config.deadLetter)

            // 5. Initialize Rule Engine
            LogManager.logDebug("CONFIG", "Initializing rule engine...")
            ruleEngine = RuleEngine(config, this) {
                forwardedCount++
                onStatsChanged?.invoke()
            }

            // 6. Initialize Inputs with message handler
            LogManager.logDebug("CONFIG", "Initializing inputs...")
            InputManager.setContext(this)
            InputManager.initialize(config) { message ->
                handleMessage(message)
            }

            // 7. Start all components
            LinkManager.connectAll()
            QueueManager.startAll()
            InputManager.startAll()

            // 8. Update tick interval from config
            normalTickIntervalMs = config.scheduler.effectiveTickInterval.millis
            chargingTickIntervalMs = config.scheduler.effectiveChargingTickInterval.millis
            wakeLockTimeoutMs = config.scheduler.wakeLockTimeout.millis
            wifiLockTimeoutMs = config.scheduler.wifiLockTimeout.millis
            val newInterval = if (isCharging) chargingTickIntervalMs else normalTickIntervalMs
            if (newInterval != tickIntervalMs) {
                tickIntervalMs = newInterval
                startTick()
                LogManager.logDebug("CONFIG", "Tick interval updated to ${newInterval}ms (charging=$isCharging)")
            }

            isRunning = true
            tickCount = 0
            refreshStats()
            saveStatus()
            acquireLocks()
            LogManager.logInfo("CONFIG", "Configuration applied successfully. Service started.")
            updateNotification()
        } catch (e: Exception) {
            LogManager.logError("CONFIG", "Failed to apply config: ${e.message}")
            e.printStackTrace()
            isRunning = false
            onStartFailed?.invoke("启动失败: ${e.message}")
        }
    }

    private fun handleMessage(message: InputMessage) {
        LogManager.log(LogLevel.DEBUG, "FS", "NATIVE handleMessage: source=${message.source}, data=${String(message.data).take(30)}")
        LogManager.log(LogLevel.DEBUG, "TRACE:FS", "handleMessage called: source=${message.source}")
        if (!isReceivingEnabled) {
            LogManager.logDebug("FS", "接收已暂停, 忽略消息: source=${message.source}, data=${String(message.data).take(200)}")
            return
        }

        receivedCount++
        onStatsChanged?.invoke()

        // Process through rule engine
        LogManager.logDebug("TRACE:FS", "Calling ruleEngine.process for ${message.source}")
        ruleEngine?.process(message)

        // Record headers
        if (message.headers.isNotEmpty()) {
            LogManager.logDebug("MESSAGE", "Headers: ${message.headers}")
        }
        LogManager.logDebug("MESSAGE", "Processed: ${message.source} -> ${String(message.data).take(1000)}")
    }

    private fun stopAll() {
        InputManager.stopAll()
        QueueManager.stopAll()
        LinkManager.disconnectAll()
        OutputManager.clear()
        releaseLocks()
        cancelEarlyTick()
        isRunning = false
        receivedCount = 0
        forwardedCount = 0
        ruleEngine?.shutdown()
        ruleEngine = null
        deadLetterHandler = null
        LogManager.logInfo("SERVICE", "All components stopped")
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

    private fun saveStatus() {
        try {
            val status = AppStatusConfig(
                configUrl = currentConfigUrl,
                isRunning = isRunning,
                isReceivingEnabled = isReceivingEnabled,
                isForwardingEnabled = isForwardingEnabled,
                isWakeLockEnabled = isWakeLockEnabled,
                isWifiLockEnabled = isWifiLockEnabled,
                autoStart = preferences.autoStart,
                appAutoStartOnBoot = preferences.autoStart
            )
            AppStatusManager.saveStatus(this, status)
        } catch (e: Exception) {
            LogManager.logWarn("APP_STATUS", "Failed to save status: ${e.message}")
        }
    }

    @Volatile
    private var wasRunningBeforeRestart = false

    private fun loadStatus() {
        try {
            val status = AppStatusManager.loadStatus(this)
            currentConfigUrl = status.configUrl
            isReceivingEnabled = status.isReceivingEnabled
            isForwardingEnabled = status.isForwardingEnabled
            isWakeLockEnabled = status.isWakeLockEnabled
            isWifiLockEnabled = status.isWifiLockEnabled
            preferences.receivingEnabled = status.isReceivingEnabled
            preferences.forwardingEnabled = status.isForwardingEnabled
            preferences.autoStart = status.autoStart
            wasRunningBeforeRestart = status.isRunning
            LogManager.logDebug("APP_STATUS", "Status loaded: running=${status.isRunning}, receive=${status.isReceivingEnabled}, forward=${status.isForwardingEnabled}, wakeLock=${status.isWakeLockEnabled}")
        } catch (e: Exception) {
            LogManager.logWarn("APP_STATUS", "Failed to load status: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        notificationDelegate.createNotificationChannels()
    }

    private fun createNotification(): Notification {
        return notificationDelegate.createNotification()
    }

    private fun updateNotification() {
        notificationDelegate.updateNotification()
    }

    private fun checkNotificationPresenceNow(reason: String) {
        lastNotificationPresenceCheckMs = System.currentTimeMillis()
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

    private fun onWakeLockAutoReleased() {
        isWakeLockEnabled = false
        saveStatus()
        notificationDelegate.invalidateStatsCache()
        updateNotification()
        onStatsChanged?.invoke()
        LogManager.logInfo("SERVICE", "WakeLock auto-released after ${wakeLockTimeoutMs / 1000}s timeout")
    }

    private fun onWifiLockAutoReleased() {
        isWifiLockEnabled = false
        saveStatus()
        notificationDelegate.invalidateStatsCache()
        updateNotification()
        onStatsChanged?.invoke()
        LogManager.logInfo("SERVICE", "WifiLock auto-released after ${wifiLockTimeoutMs / 1000}s timeout")
    }
}
