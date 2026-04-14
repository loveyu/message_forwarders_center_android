package info.loveyu.mfca.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import info.loveyu.mfca.InputMethodFloatingActivity
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
        const val NOTIFICATION_ID = 1
        const val ACTION_INIT = "info.loveyu.mfca.action.INIT"
        const val ACTION_START = "info.loveyu.mfca.action.START"
        const val ACTION_STOP = "info.loveyu.mfca.action.STOP"
        const val ACTION_TOGGLE_RECEIVE = "info.loveyu.mfca.action.TOGGLE_RECEIVE"
        const val ACTION_TOGGLE_FORWARD = "info.loveyu.mfca.action.TOGGLE_FORWARD"
        const val ACTION_RELOAD_CONFIG = "info.loveyu.mfca.action.RELOAD_CONFIG"

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
            private set

        fun isServiceAlive(): Boolean = serviceInstance != null

        fun refreshNotification() {
            serviceInstance?.updateNotification()
        }

        fun refreshStats() {
            // Only count enabled components based on whenCondition/deny
            val config = currentConfig
            if (config != null) {
                val ctx = serviceInstance!!
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

    // Stats refresh scheduler (5s)
    private val statsScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var statsFuture: ScheduledFuture<*>? = null

    // Config application executor (off main thread)
    private val configExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile
    private var isApplyingConfig = false

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        preferences = Preferences(this)
        createNotificationChannel()

        // Start periodic stats refresh (5s)
        statsFuture = statsScheduler.scheduleAtFixedRate({
            if (isRunning) {
                refreshStats()
            }
        }, 5, 5, TimeUnit.SECONDS)
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
                updateNotification()
                onStatsChanged?.invoke()
                return START_STICKY
            }
            ACTION_TOGGLE_FORWARD -> {
                isForwardingEnabled = !isForwardingEnabled
                preferences.forwardingEnabled = isForwardingEnabled
                saveStatus()
                updateNotification()
                onStatsChanged?.invoke()
                return START_STICKY
            }
            ACTION_RELOAD_CONFIG -> {
                reloadConfig()
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
        statsFuture?.cancel(false)
        statsScheduler.shutdown()
        configExecutor.shutdown()
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

        configExecutor.execute {
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

            isRunning = true
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
            LogManager.log("TRACE:FS", "Receiving disabled, dropping message")
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
            preferences.receivingEnabled = status.isReceivingEnabled
            preferences.forwardingEnabled = status.isForwardingEnabled
            preferences.autoStart = status.autoStart
            wasRunningBeforeRestart = status.isRunning
            LogManager.log("APP_STATUS", "Status loaded: running=${status.isRunning}, receive=${status.isReceivingEnabled}, forward=${status.isForwardingEnabled}")
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
            "L${linkCount} I${inputCount} O${outputCount} · R${receivedCount} S${forwardedCount}"
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
            // Toggle receive action
            val receiveIntent = Intent(this, ForwardService::class.java).apply {
                action = ACTION_TOGGLE_RECEIVE
            }
            val receivePendingIntent = PendingIntent.getService(
                this, 1, receiveIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val receiveTitle = if (isReceivingEnabled) getString(R.string.notification_action_stop_receive)
            else getString(R.string.notification_action_resume_receive)

            // Toggle forward action
            val forwardIntent = Intent(this, ForwardService::class.java).apply {
                action = ACTION_TOGGLE_FORWARD
            }
            val forwardPendingIntent = PendingIntent.getService(
                this, 2, forwardIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val forwardTitle = if (isForwardingEnabled) getString(R.string.notification_action_stop_forward)
            else getString(R.string.notification_action_resume_forward)

            builder.addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notification_receive),
                receiveTitle,
                receivePendingIntent
            ).build())
            builder.addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notification_forward),
                forwardTitle,
                forwardPendingIntent
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
            "L${linkCount} I${inputCount} O${outputCount} · R${receivedCount} S${forwardedCount}"
        } else {
            "已停止"
        }
        if (statsText == lastNotificationStats) return
        lastNotificationStats = statsText
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun acquireLocks() {
        // Acquire partial wake lock to keep CPU alive when screen is off
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
        // Acquire WiFi lock to keep WiFi connection alive when screen is off
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

    private fun releaseLocks() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                LogManager.log("SERVICE", "WakeLock released")
            }
        }
        wakeLock = null
        wifiLock?.let {
            if (it.isHeld) {
                it.release()
                LogManager.log("SERVICE", "WifiLock released")
            }
        }
        wifiLock = null
    }
}
