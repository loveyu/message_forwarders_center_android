package info.loveyu.mfca.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import info.loveyu.mfca.InputMethodFloatingActivity
import info.loveyu.mfca.StatusFloatingActivity
import info.loveyu.mfca.MainActivity
import info.loveyu.mfca.R
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
import info.loveyu.mfca.server.HttpServer
import info.loveyu.mfca.server.MessageForwarder
import info.loveyu.mfca.util.AppStatusManager
import info.loveyu.mfca.util.NetworkChecker
import info.loveyu.mfca.util.LogLevel
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.Preferences
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ForwardService : Service() {

    companion object {
        private const val TAG = "ForwardService"
        const val CHANNEL_ID = "forward_service_channel"
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

        /** 失败计数重置间隔：每 20 个 tick（tick=30s 时约 10 分钟） */
        private const val FAILURE_RESET_TICK_INTERVAL = 20

        /** 事件触发 tick 的最小间隔：5 秒，防止事件风暴 */
        private const val MIN_TICK_INTERVAL_MS = 5_000L

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

        @Volatile
        private var lastNotificationStats: String? = null

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
                LogManager.log("CONFIG", "Failed to load config: ${e.message}")
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

    // WakeLock: keeps CPU running when screen is off
    private var wakeLock: PowerManager.WakeLock? = null
    // WifiLock: keeps WiFi connection alive when screen is off
    private var wifiLock: WifiManager.WifiLock? = null

    // New architecture components
    private var ruleEngine: RuleEngine? = null
    private var deadLetterHandler: DeadLetterHandler? = null

    // 统一调度器：替代原来分散的 statsScheduler + configExecutor
    // 同时处理周期性 tick 和一次性 config 加载任务
    private val appScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var tickFuture: ScheduledFuture<*>? = null
    private var tickCount = 0

    // tick 间隔，从 config.scheduler 读取，默认 30s
    @Volatile
    private var tickIntervalMs: Long = 30_000L

    // 上次 tick 执行时间，用于事件触发时的最小间隔判断
    @Volatile
    private var lastTickTime: Long = 0L

    // 屏幕亮起广播接收器（动态注册，SCREEN_ON 无法静态注册）
    private var screenOnReceiver: BroadcastReceiver? = null

    // 充电状态：影响 tick 间隔
    @Volatile
    private var isCharging = false

    // 配置的两个间隔值
    @Volatile
    private var normalTickIntervalMs: Long = 30_000L
    @Volatile
    private var chargingTickIntervalMs: Long = 30_000L

    @Volatile
    private var isApplyingConfig = false

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        preferences = Preferences(this)
        createNotificationChannel()
        startTick()
        registerScreenOnReceiver()
    }

    /**
     * 启动统一 Ticker
     */
    private fun startTick() {
        tickFuture?.cancel(false)
        tickFuture = appScheduler.scheduleAtFixedRate({
            onTick()
        }, tickIntervalMs, tickIntervalMs, TimeUnit.MILLISECONDS)
    }

    /**
     * 统一 Ticker 回调：所有定时检查集中执行
     */
    private fun onTick() {
        if (!isRunning) return
        lastTickTime = System.currentTimeMillis()
        tickCount++

        // 1. 刷新统计 & 通知栏
        refreshStats()

        // 2. Link 健康检查 + MQTT 心跳日志
        LinkManager.onTick()

        // 3. Input 健康检查
        InputManager.onTick()

        // 4. SqliteQueue 处理（根据各自的 retryInterval 判断是否到期）
        QueueManager.onTick()

        // 5. 每 N 个 tick 执行失败重置（≈10 分钟）
        if (tickCount % FAILURE_RESET_TICK_INTERVAL == 0) {
            LinkManager.onFailureResetTick()
        }
    }

    /**
     * 由外部事件触发一次提前 tick。
     * 检查最小触发间隔，满足则立即执行并重置周期定时器。
     */
    private fun doTriggerTick() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTickTime
        if (elapsed < MIN_TICK_INTERVAL_MS) {
            LogManager.logDebug("SERVICE", "TriggerTick debounced: ${elapsed}ms < ${MIN_TICK_INTERVAL_MS}ms")
            return
        }
        LogManager.logDebug("SERVICE", "TriggerTick: event-driven early tick (last was ${elapsed}ms ago)")
        appScheduler.execute {
            onTick()
            // 重置周期定时器：从现在开始重新计时
            startTick()
        }
    }

    /**
     * 动态注册系统事件广播接收器。
     * ACTION_SCREEN_ON 是受保护广播，只能动态注册。
     * 同时监听充放电事件以动态调整 tick 间隔。
     */
    private fun registerScreenOnReceiver() {
        if (screenOnReceiver != null) return
        screenOnReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        LogManager.logDebug("SERVICE", "Screen on, triggering tick")
                        doTriggerTick()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        LogManager.logDebug("SERVICE", "User present (unlocked), triggering tick")
                        doTriggerTick()
                    }
                    Intent.ACTION_POWER_CONNECTED -> {
                        LogManager.logDebug("SERVICE", "Power connected, switching to charging interval")
                        updateChargingState(true)
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        LogManager.logDebug("SERVICE", "Power disconnected, switching to normal interval")
                        updateChargingState(false)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(screenOnReceiver, filter)
        // 检测当前充电状态
        detectInitialChargingState()
    }

    private fun detectInitialChargingState() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        isCharging = bm.isCharging
        tickIntervalMs = if (isCharging) chargingTickIntervalMs else normalTickIntervalMs
        LogManager.logDebug("SERVICE", "Initial charging state: $isCharging, tickInterval=${tickIntervalMs}ms")
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

    private fun unregisterScreenOnReceiver() {
        screenOnReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
            screenOnReceiver = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
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
                lastNotificationStats = null
                updateNotification()
                onStatsChanged?.invoke()
                LogManager.logInfo("SERVICE", if (isReceivingEnabled) "已恢复接收" else "已暂停接收")
                return START_STICKY
            }
            ACTION_TOGGLE_FORWARD -> {
                isForwardingEnabled = !isForwardingEnabled
                preferences.forwardingEnabled = isForwardingEnabled
                saveStatus()
                lastNotificationStats = null
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
                lastNotificationStats = null
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
                lastNotificationStats = null
                updateNotification()
                onStatsChanged?.invoke()
                LogManager.logInfo("SERVICE", if (isWifiLockEnabled) "已启用 WifiLock" else "已关闭 WifiLock")
                return START_STICKY
            }
            ACTION_RELOAD_CONFIG -> {
                stopAll()
                start()
                return START_STICKY
            }
            "OPEN_NOTIFICATION" -> {
                val outputName = intent.getStringExtra("output_name") ?: ""
                val notificationTag = intent.getStringExtra("notification_tag") ?: ""
                val notificationId = intent.getIntExtra("notification_id", -1)
                LogManager.logInfo("INTERNAL", "Notification opened: output=$outputName, tag=$notificationTag, id=$notificationId")
                return START_STICKY
            }
        }

        // Load status from YAML config
        loadStatus()

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (intent?.action == ACTION_START) {
            start()
        } else if (intent?.action == null && wasRunningBeforeRestart) {
            // Service restarted by system (START_STICKY) after being killed
            // Auto-restore previously running service
            LogManager.log("SERVICE", "Auto-restoring service after system restart")
            wasRunningBeforeRestart = false
            start()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep service running when user swipes app from recents
        if (isRunning) {
            LogManager.log("SERVICE", "Task removed, restarting service to keep running")
            val restartIntent = Intent(this, ForwardService::class.java).apply {
                action = ACTION_START
            }
            startService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        tickFuture?.cancel(false)
        appScheduler.shutdown()
        unregisterScreenOnReceiver()
        serviceInstance = null
        stopAll()
        super.onDestroy()
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
                LogManager.log("CONFIG", "Failed to load saved config: ${e.message}")
            }
        }

        // No valid config, just mark as not running
        isRunning = false
        LogManager.log("SERVICE", "No valid config found, service not started")
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
            updateNotification()

            val target = preferences.forwardTarget
            if (target.isNotEmpty() && isForwardingEnabled) {
                MessageForwarder.forward(target, body) { success ->
                    if (success) {
                        forwardedCount++
                        onStatsChanged?.invoke()
                        updateNotification()
                    }
                }
            }
        }
        httpServer?.startServer()
        isRunning = true
        saveStatus()
        acquireLocks()
        LogManager.log("SERVICE", "Legacy mode started on port $port")
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
            LogManager.log("CONFIG", "Config application already in progress, skipping duplicate")
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
        LogManager.log("CONFIG", "Applying new configuration...")

        // Stop existing components
        stopAll()

        currentConfig = config
        legacyMode = false

        // Initialize components in order
        try {
            // 1. Initialize Links
            LogManager.log("CONFIG", "Initializing links...")
            LinkManager.setContext(this)
            LinkManager.initialize(config)

            // 2. Initialize Queues
            LogManager.log("CONFIG", "Initializing queues...")
            QueueManager.initialize(this, config)

            // 3. Initialize Outputs
            LogManager.log("CONFIG", "Initializing outputs...")
            OutputManager.initialize(this, config)

            // 4. Initialize Dead Letter Handler
            deadLetterHandler = DeadLetterHandler(this, config.deadLetter)

            // 5. Initialize Rule Engine
            LogManager.log("CONFIG", "Initializing rule engine...")
            ruleEngine = RuleEngine(config, this) {
                forwardedCount++
                onStatsChanged?.invoke()
                updateNotification()
            }

            // 6. Initialize Inputs with message handler
            LogManager.log("CONFIG", "Initializing inputs...")
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
            val newInterval = if (isCharging) chargingTickIntervalMs else normalTickIntervalMs
            if (newInterval != tickIntervalMs) {
                tickIntervalMs = newInterval
                startTick()
                LogManager.log("CONFIG", "Tick interval updated to ${newInterval}ms (charging=$isCharging)")
            }

            isRunning = true
            tickCount = 0
            refreshStats()
            saveStatus()
            acquireLocks()
            LogManager.log("CONFIG", "Configuration applied successfully. Service started.")
            updateNotification()
        } catch (e: Exception) {
            LogManager.log("CONFIG", "Failed to apply config: ${e.message}")
            e.printStackTrace()
            isRunning = false
            onStartFailed?.invoke("启动失败: ${e.message}")
        }
    }

    private fun handleMessage(message: InputMessage) {
        LogManager.log(LogLevel.DEBUG, "FS", "NATIVE handleMessage: source=${message.source}, data=${String(message.data).take(30)}")
        LogManager.log(LogLevel.DEBUG, "TRACE:FS", "handleMessage called: source=${message.source}")
        if (!isReceivingEnabled) {
            LogManager.logInfo("FS", "接收已暂停, 忽略消息: source=${message.source}, data=${String(message.data).take(200)}")
            return
        }

        receivedCount++
        onStatsChanged?.invoke()
        updateNotification()

        // Process through rule engine
        LogManager.log("TRACE:FS", "Calling ruleEngine.process for ${message.source}")
        ruleEngine?.process(message)

        // Record headers
        if (message.headers.isNotEmpty()) {
            LogManager.log("MESSAGE", "Headers: ${message.headers}")
        }
        LogManager.log("MESSAGE", "Processed: ${message.source} -> ${String(message.data).take(1000)}")
    }

    private fun reloadConfig() {
        val savedConfig = preferences.loadFullConfig()
        if (savedConfig != null && savedConfig.isNotBlank()) {
            try {
                val config = ConfigLoader.loadConfig(savedConfig)
                applyConfig(config)
                LogManager.log("CONFIG", "Config reloaded successfully")
            } catch (e: Exception) {
                LogManager.log("CONFIG", "Failed to reload config: ${e.message}")
            }
        } else {
            LogManager.log("CONFIG", "No saved config to reload")
        }
    }

    private fun stopAll() {
        InputManager.stopAll()
        QueueManager.stopAll()
        LinkManager.disconnectAll()
        OutputManager.clear()
        releaseLocks()
        isRunning = false
        receivedCount = 0
        forwardedCount = 0
        ruleEngine?.shutdown()
        ruleEngine = null
        deadLetterHandler = null
        LogManager.log("SERVICE", "All components stopped")
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
            LogManager.log("APP_STATUS", "Failed to save status: ${e.message}")
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
            LogManager.log("APP_STATUS", "Status loaded: running=${status.isRunning}, receive=${status.isReceivingEnabled}, forward=${status.isForwardingEnabled}, wakeLock=${status.isWakeLockEnabled}")
        } catch (e: Exception) {
            LogManager.log("APP_STATUS", "Failed to load status: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val linkErrorChannel = NotificationChannel(
            LINK_ERROR_CHANNEL_ID,
            getString(R.string.link_error_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        linkErrorChannel.group = LINK_ERROR_GROUP_ID
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(LINK_ERROR_GROUP_ID, getString(R.string.link_error_channel_name))
        )
        manager.createNotificationChannel(linkErrorChannel)
    }

    private fun createNotification(): Notification {
        val contentIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 状态栏显示: L链路数 I输入数 O输出数 R接收 S发送
        val statsText = if (isRunning) {
            buildString {
                append("L${linkCount} I${inputCount} O${outputCount} · R${receivedCount} S${forwardedCount}")
                if (!isReceivingEnabled) append(" | 暂停接收")
                if (!isForwardingEnabled) append(" | 暂停转发")
                if (isWakeLockEnabled) append(" | W锁")
                if (isWifiLockEnabled) append(" | WiFi锁")
            }
        } else {
            "已停止"
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(statsText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        // Only show action buttons when service is running
        if (isRunning) {
            // Status toggle action - opens floating panel
            val statusIntent = Intent(this, StatusFloatingActivity::class.java)
            val statusPendingIntent = PendingIntent.getActivity(
                this, 1, statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notification_receive),
                getString(R.string.notification_action_status),
                statusPendingIntent
            ).build())
        }

        // Input method switch action (always visible, controlled by config)
        if (currentConfig?.quickSettings?.inputMethodSwitcher != false) {
            val inputMethodIntent = Intent(this, InputMethodFloatingActivity::class.java)
            val inputMethodPendingIntent = PendingIntent.getActivity(
                this, 3, inputMethodIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_tile_input_method),
                getString(R.string.notification_action_input_method),
                inputMethodPendingIntent
            ).build())
        }

        return builder.build()
    }

    private fun updateNotification() {
        val statsText = if (isRunning) {
            buildString {
                append("L${linkCount} I${inputCount} O${outputCount} · R${receivedCount} S${forwardedCount}")
                if (!isReceivingEnabled) append(" | 暂停接收")
                if (!isForwardingEnabled) append(" | 暂停转发")
                if (isWakeLockEnabled) append(" | W锁")
                if (isWifiLockEnabled) append(" | WiFi锁")
            }
        } else {
            "已停止"
        }
        if (statsText == lastNotificationStats) return
        lastNotificationStats = statsText
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun acquireLocks() {
        // Acquire partial wake lock only when enabled (to avoid battery drain)
        if (isWakeLockEnabled) {
            acquireWakeLock()
        }
        // Acquire WiFi lock only when enabled (to avoid WLAN battery drain)
        if (isWifiLockEnabled) {
            acquireWifiLock()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "mfca::forward_service"
            ).apply {
                acquire()
            }
            LogManager.log("SERVICE", "WakeLock acquired")
        }
    }

    private fun acquireWifiLock() {
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(wifiMode, "mfca::forward_service").apply {
                acquire()
            }
            LogManager.log("SERVICE", "WifiLock acquired")
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let {
            if (it.isHeld) {
                it.release()
                LogManager.log("SERVICE", "WifiLock released")
            }
        }
        wifiLock = null
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                LogManager.log("SERVICE", "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun releaseLocks() {
        releaseWakeLock()
        releaseWifiLock()
    }
}
