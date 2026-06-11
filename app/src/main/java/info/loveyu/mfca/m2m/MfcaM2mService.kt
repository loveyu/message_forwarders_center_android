package info.loveyu.mfca.m2m

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.ParcelFileDescriptor
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import info.loveyu.mfca.MainActivity
import info.loveyu.mfca.R
import info.loveyu.mfca.plugin.M2mPluginCore
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.NetworkChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

class MfcaM2mService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var tunInterface: ParcelFileDescriptor? = null
    @Volatile private var runtimeSessionId: Long = 0L
    @Volatile private var retryAttempts: Int = 0
    private var retryJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "null"
        val forceRestart = intent?.getBooleanExtra(EXTRA_FORCE_RESTART, false) ?: false
        LogManager.logInfo("VPN", "MfcaM2mService onStartCommand: action=$action, forceRestart=$forceRestart, startId=$startId")
        when (action) {
            ACTION_ENABLE -> {
                startVpnForeground()
                serviceScope.launch {
                    M2mManager.setEnabled(true)
                    syncRuntime(forceRestart = forceRestart, isRetry = false)
                }
            }

            ACTION_REFRESH -> {
                startVpnForeground()
                serviceScope.launch {
                    syncRuntime(forceRestart = forceRestart, isRetry = false)
                }
            }

            ACTION_DISABLE -> {
                startVpnForeground()
                serviceScope.launch {
                    stopRuntime(disableVpn = true, stopService = true)
                }
            }

            else -> LogManager.logWarn("VPN", "Unknown action received: $action")
        }
        return START_STICKY
    }

    private fun startVpnForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ForwardService.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(ForwardService.NOTIFICATION_ID, notification)
        }
        ForwardService.refreshNotification()
    }

    override fun onRevoke() {
        LogManager.logWarn("VPN", "VPN permission revoked by system")
        serviceScope.launch {
            stopRuntime(disableVpn = true, stopService = true)
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        val runningName = M2mManager.state.value.runningCandidateName
        LogManager.logInfo("VPN", "MfcaM2mService onDestroy, running candidate: $runningName")
        cancelPendingRetry("service destroyed")
        closeTunInterface()
        M2mBridgeProcessManager.stop()
        M2mProcessManager.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun syncRuntime(forceRestart: Boolean, isRetry: Boolean) {
        val candidateName = M2mManager.getSelectedCandidate()?.config?.name ?: "?"
        LogManager.logInfo("VPN", "syncRuntime: candidate=$candidateName, forceRestart=$forceRestart, isRetry=$isRetry")
        if (!isRetry) {
            cancelPendingRetry("runtime sync requested")
            retryAttempts = 0
        }
        if (M2mManager.state.value.runtimeStatus == M2mRuntimeStatus.preparing) {
            LogManager.logDebug("VPN", "Skipping sync, preparation already in progress")
            return
        }
        if (prepare(this) != null) {
            LogManager.logWarn("VPN", "VPN permission required, launching prepare intent")
            resetRuntimeSession()
            M2mManager.clearRunningCandidate(M2mRuntimeStatus.error, getString(R.string.vpn_permission_required))
            updateNotification()
            return
        }

        var selected = M2mManager.getSelectedCandidate()
        if (selected == null) {
            LogManager.logWarn("VPN", "No available candidate selected, attempting auto-download")
            autoDownloadFirstMatch()
            selected = M2mManager.getSelectedCandidate()
        }

        if (selected == null) {
            LogManager.logWarn("VPN", "No available candidate after auto-download, disabling VPN")
            resetRuntimeSession()
            M2mBridgeProcessManager.stop()
            closeTunInterface()
            M2mProcessManager.stop()
            M2mManager.setEnabled(false)
            M2mManager.clearRunningCandidate(M2mRuntimeStatus.error, "当前没有可用候选")
            updateNotification()
            return
        }

        val current = M2mProcessManager.current()
        val currentBridge = M2mBridgeProcessManager.current()
        if (!forceRestart && current?.candidateName == selected.config.name && currentBridge?.candidateName == selected.config.name) {
            val message = "m2m 运行中: ${selected.config.name}"
            LogManager.logInfo("VPN", "Candidate ${selected.config.name} already running, skipping sync")
            M2mManager.markRunning(selected.config.name, message)
            updateNotification()
            return
        }

        if (current != null || currentBridge != null || tunInterface != null) {
            LogManager.logInfo("VPN", "Stopping previous runtime (${current?.candidateName}) before starting ${selected.config.name}")
            M2mManager.updateRuntimeStatus(M2mRuntimeStatus.stopping, "Stopping ${selected.config.name}")
            updateNotification()
            stopRuntime(disableVpn = false, stopService = false)
        }

        val sessionId = nextRuntimeSessionId()

        // Ensure config is cached before proceeding
        val cachedFile = M2mConfigCacheManager.getCachedSourceFile(this, selected.config.name)
        if (cachedFile == null) {
            LogManager.logInfo("VPN", "Config not cached for ${selected.config.name}, downloading from ${selected.config.configUrl}")
            M2mManager.updateRuntimeStatus(M2mRuntimeStatus.preparing, "Downloading config for ${selected.config.name}")
            updateNotification()
            M2mConfigCacheManager.downloadConfig(this, selected.config).onFailure { error ->
                LogManager.logError("VPN", "Config download failed for ${selected.config.name}: ${error.message}")
                handleRuntimeFailure(
                    candidateName = selected.config.name,
                    sessionId = sessionId,
                    message = error.message ?: "Failed to download config",
                )
                return
            }
        } else {
            LogManager.logDebug("VPN", "Config already cached for ${selected.config.name}")
        }

        val artifacts = M2mManager.prepareSelectedCandidate(this).getOrElse { error ->
            handleRuntimeFailure(
                candidateName = selected.config.name,
                sessionId = sessionId,
                message = error.message ?: "Failed to prepare m2m",
            )
            return
        }

        M2mManager.updateRuntimeStatus(M2mRuntimeStatus.starting, "Starting ${artifacts.candidate.name}")
        updateNotification()
        LogManager.logDebug("VPN", "Artifacts: port=${artifacts.localProxyPort}, apiPort=${artifacts.apiPort}, udpRelay=${artifacts.udpRelay}, dnsHijack=${artifacts.dnsHijack}, core=${artifacts.coreFilePath}, profile=${artifacts.profileFilePath}")

        // Enable socket protection so m2m outbound connections bypass VPN tunnel
        M2mPluginCore.socketProtector = M2mPluginCore.SocketProtector { fd -> protect(fd) }
        LogManager.logDebug("VPN", "Socket protector registered")

        val runningCore = M2mProcessManager.start(
            context = this,
            artifacts = artifacts,
            onUnexpectedExit = { exitCode, tail ->
                serviceScope.launch {
                    handleUnexpectedRuntimeExit(
                        candidateName = artifacts.candidate.name,
                        sessionId = sessionId,
                        message = "m2m exited ($exitCode): $tail",
                    )
                }
            },
        ).getOrElse { error ->
            handleRuntimeFailure(
                candidateName = artifacts.candidate.name,
                sessionId = sessionId,
                message = error.message ?: "Failed to start m2m",
            )
            return
        }
        LogManager.logDebug("VPN", "m2m core started: ${runningCore.candidateName}")
        setPendingLogLevel(artifacts.apiPort, artifacts.apiSecret, artifacts.logLevel)

        val tun = establishTun(selected, artifacts) ?: run {
            M2mProcessManager.stop()
            handleRuntimeFailure(
                candidateName = artifacts.candidate.name,
                sessionId = sessionId,
                message = getString(R.string.vpn_establish_failed),
            )
            return
        }
        tunInterface = tun
        val ifaceName = findTunInterfaceName()
        LogManager.logInfo("VPN", "Established TUN for ${artifacts.candidate.name}, interface=$ifaceName")
        M2mManager.onTunEstablished(ifaceName)
        LogManager.logDebug("VPN", "TUN fd=${tun.fd}, mtu=$TUN_MTU, gateway=$TUN_GATEWAY/$TUN_SUBNET_PREFIX, dns=$TUN_DNS_PRIMARY/$TUN_DNS_SECONDARY")

        val runningBridge = M2mBridgeProcessManager.start(
            context = this,
            artifacts = artifacts,
            tunInterface = tun,
            onUnexpectedExit = { exitCode, tail ->
                serviceScope.launch {
                    handleUnexpectedRuntimeExit(
                        candidateName = artifacts.candidate.name,
                        sessionId = sessionId,
                        message = "m2m bridge exited ($exitCode): $tail",
                    )
                }
            },
        ).getOrElse { error ->
            LogManager.logError("VPN", "Failed to start bridge: ${error.message}")
            closeTunInterface()
            M2mProcessManager.stop()
            handleRuntimeFailure(
                candidateName = artifacts.candidate.name,
                sessionId = sessionId,
                message = error.message ?: "Failed to start m2m bridge",
            )
            return
        }
        LogManager.logDebug("VPN", "VPN bridge started: ${runningBridge.candidateName}")

        cancelPendingRetry("runtime started")
        retryAttempts = 0
        val message = "m2m 运行中: ${runningBridge.candidateName}"
        M2mManager.markRunning(runningCore.candidateName, message)
        updateNotification()
        LogManager.logInfo("VPN", "VPN runtime started for ${runningCore.candidateName}")
    }

    private fun autoDownloadFirstMatch() {
        val candidates = M2mManager.state.value.candidates
        for (candidate in candidates) {
            if (!candidate.config.enabled) continue
            val networkResult =
                NetworkChecker.getEnableReason(this, candidate.config.whenCondition, candidate.config.deny)
            if (!networkResult.enabled) continue

            LogManager.logInfo("VPN", "Auto-downloading config for ${candidate.config.name}")
            M2mManager.updateRuntimeStatus(
                M2mRuntimeStatus.preparing,
                "Downloading config for ${candidate.config.name}",
            )
            updateNotification()

            val result = M2mConfigCacheManager.downloadConfig(this, candidate.config)
            result.onSuccess { M2mManager.selectCandidate(candidate.config.name) }
            result.onFailure { error ->
                LogManager.logWarn("VPN", "Auto-download failed for ${candidate.config.name}: ${error.message}")
            }
            return
        }
    }

    private fun stopRuntime(disableVpn: Boolean, stopService: Boolean) {
        val runningName = M2mManager.state.value.runningCandidateName
        LogManager.logInfo("VPN", "Stopping runtime: candidate=$runningName, disableVpn=$disableVpn, stopService=$stopService")
        cancelPendingRetry("runtime stopping")
        resetRuntimeSession()
        M2mManager.onTunDestroyed()
        clearPendingLogLevel()
        M2mConfigCacheManager.cancelDownload()
        M2mCoreManager.cancelDownload()
        M2mPluginCore.socketProtector = null
        val bridgeStopped = M2mBridgeProcessManager.stop()
        closeTunInterface()
        val stopped = M2mProcessManager.stop() ?: bridgeStopped
        if (disableVpn) {
            retryAttempts = 0
            M2mManager.setEnabled(false)
        } else {
            M2mManager.clearRunningCandidate(
                status = M2mRuntimeStatus.idle,
                message = if (stopped == null) "m2m 已停止" else "Stopped $stopped",
            )
        }
        if (stopService) {
            LogManager.logInfo("VPN", "Stopping MfcaM2mService (foreground+self)")
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        } else {
            updateNotification()
        }
    }

    private suspend fun handleUnexpectedRuntimeExit(candidateName: String, sessionId: Long, message: String) {
        handleRuntimeFailure(candidateName, sessionId, message)
    }

    private suspend fun handleRuntimeFailure(candidateName: String, sessionId: Long, message: String) {
        val retryReason = nextRetryReason(candidateName, sessionId)
        LogManager.logError("VPN", "Runtime failure for $candidateName: $message (retryReason=$retryReason)")
        resetRuntimeSession()
        M2mPluginCore.socketProtector = null
        M2mBridgeProcessManager.stop()
        closeTunInterface()
        M2mProcessManager.stop()

        if (retryReason == null) {
            LogManager.logWarn("VPN", "Scheduling retry #${retryAttempts + 1} for $candidateName")
            scheduleRetry(candidateName, message)
            return
        }

        retryAttempts = 0
        LogManager.logInfo("VPN", "Skip VPN retry for $candidateName: $retryReason")
        M2mManager.setEnabled(false)
        M2mManager.clearRunningCandidate(M2mRuntimeStatus.error, message)
        updateNotification()
    }

    private fun nextRetryReason(candidateName: String, sessionId: Long): String? {
        if (sessionId != runtimeSessionId) {
            LogManager.logDebug("VPN", "Retry not allowed for $candidateName: sessionId mismatch ($sessionId != $runtimeSessionId)")
            return "runtime already replaced by another m2m candidate"
        }
        val state = M2mManager.state.value
        if (!state.isEnabled) {
            LogManager.logDebug("VPN", "Retry not allowed for $candidateName: vpn is disabled")
            return "vpn is disabled"
        }
        if (state.activeCandidateName != candidateName) {
            LogManager.logDebug("VPN", "Retry not allowed for $candidateName: active candidate changed to ${state.activeCandidateName}")
            return "another m2m candidate is now preferred"
        }
        if (retryAttempts >= MAX_RETRY_ATTEMPTS) {
            LogManager.logDebug("VPN", "Retry not allowed for $candidateName: retry limit reached ($retryAttempts/$MAX_RETRY_ATTEMPTS)")
            return "retry limit reached"
        }
        return null
    }

    private fun scheduleRetry(candidateName: String, message: String) {
        cancelPendingRetry("scheduling next retry")
        retryAttempts += 1
        val retryMessage = getString(R.string.vpn_retrying, candidateName, retryAttempts, MAX_RETRY_ATTEMPTS)
        LogManager.logWarn("VPN", "$message. $retryMessage")
        M2mManager.clearRunningCandidate(M2mRuntimeStatus.error, retryMessage)
        updateNotification()
        retryJob = serviceScope.launch {
            delay(RETRY_DELAY_MS * retryAttempts)
            val state = M2mManager.state.value
            if (!state.isEnabled || state.activeCandidateName != candidateName) {
                LogManager.logInfo("VPN", "Cancel pending retry for $candidateName because runtime target changed")
                return@launch
            }
            LogManager.logInfo("VPN", "Retrying VPN startup for $candidateName")
            syncRuntime(forceRestart = true, isRetry = true)
        }
    }

    private fun cancelPendingRetry(reason: String) {
        if (retryJob?.isActive == true) {
            LogManager.logInfo("VPN", "Canceling pending VPN retry: $reason")
        }
        retryJob?.cancel()
        retryJob = null
    }

    companion object {
        @Volatile private var pendingApiPort: Int = 0
        @Volatile private var pendingApiSecret: String? = null
        @Volatile private var pendingLogLevel: M2mLogLevel? = null

        fun setPendingLogLevel(apiPort: Int, apiSecret: String?, logLevel: M2mLogLevel?) {
            pendingApiPort = apiPort
            pendingApiSecret = apiSecret
            pendingLogLevel = logLevel
        }

        fun clearPendingLogLevel() {
            pendingApiPort = 0
            pendingApiSecret = null
            pendingLogLevel = null
        }

        fun tryApplyPendingLogLevel() {
            val port = pendingApiPort
            val secret = pendingApiSecret
            val level = pendingLogLevel ?: return
            if (port == 0) return
            clearPendingLogLevel()
            CoroutineScope(Dispatchers.IO).launch {
                val maxAttempts = 5
                val delayMs = 1000L
                var lastError: String? = null
                for (attempt in 1..maxAttempts) {
                    try {
                        val url = URL("http://127.0.0.1:$port/configs")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "PATCH"
                        conn.doOutput = true
                        conn.connectTimeout = 2000
                        conn.readTimeout = 2000
                        conn.setRequestProperty("Content-Type", "application/json")
                        if (!secret.isNullOrBlank()) {
                            conn.setRequestProperty("Authorization", "Bearer $secret")
                        }
                        val body = """{"log-level": "${level.name}"}"""
                        conn.outputStream.write(body.toByteArray())
                        val code = conn.responseCode
                        conn.disconnect()
                        if (code in 200..299) {
                            LogManager.logDebug("VPN", "Applied log-level via API: ${level.name}")
                            return@launch
                        }
                        lastError = "HTTP $code"
                        if (code != 502) {
                            val error = conn.errorStream?.bufferedReader()?.readText() ?: ""
                            LogManager.logWarn("VPN", "Failed to apply log-level via API: $lastError $error")
                            return@launch
                        }
                    } catch (e: Exception) {
                        lastError = e.message
                        if (attempt < maxAttempts) {
                            delay(delayMs)
                            continue
                        }
                    }
                    if (attempt < maxAttempts) {
                        delay(delayMs)
                    }
                }
                LogManager.logWarn("VPN", "Failed to apply log-level after $maxAttempts attempts: $lastError")
            }
        }

        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 5_000L
        const val TUN_MTU = 1500
        const val TUN_SUBNET_PREFIX = 30
        const val TUN_GATEWAY = "172.19.0.1"
        const val TUN_PORTAL = "172.19.0.2"
        const val TUN_GATEWAY_CIDR = "$TUN_GATEWAY/$TUN_SUBNET_PREFIX"
        const val TUN_DNS_PRIMARY = "1.1.1.1"
        const val TUN_DNS_SECONDARY = "8.8.8.8"
        const val NET_ANY = "0.0.0.0"
        const val M2M_DNS_PORT = 1053
        const val MAPDNS_NETWORK = "100.64.0.0"
        const val MAPDNS_NETMASK = "255.192.0.0"
        const val MAPDNS_CACHE_SIZE = 10000

        const val ACTION_ENABLE = "info.loveyu.mfca.action.ENABLE_VPN"
        const val ACTION_REFRESH = "info.loveyu.mfca.action.REFRESH_VPN"
        const val ACTION_DISABLE = "info.loveyu.mfca.action.DISABLE_VPN"
        private const val EXTRA_FORCE_RESTART = "force_restart"

        fun enableIntent(context: Context): Intent = Intent(context, MfcaM2mService::class.java).apply {
            action = ACTION_ENABLE
        }

        fun refreshIntent(context: Context, forceRestart: Boolean = false): Intent =
            Intent(context, MfcaM2mService::class.java).apply {
                action = ACTION_REFRESH
                putExtra(EXTRA_FORCE_RESTART, forceRestart)
            }

        fun disableIntent(context: Context): Intent = Intent(context, MfcaM2mService::class.java).apply {
            action = ACTION_DISABLE
        }

        fun sync(context: Context, forceRestart: Boolean = false) {
            ContextCompat.startForegroundService(context, refreshIntent(context, forceRestart))
        }
    }

    private fun nextRuntimeSessionId(): Long {
        val next = System.nanoTime()
        runtimeSessionId = next
        return next
    }

    private fun resetRuntimeSession() {
        runtimeSessionId = 0L
    }

    private fun establishTun(candidate: info.loveyu.mfca.m2m.M2mCandidateState, artifacts: PreparedM2mArtifacts): ParcelFileDescriptor? {
        val accessControl = candidate.effectiveAccessControlMode
        val packageCount = candidate.effectivePackages.size
        LogManager.logInfo("VPN", "Establishing TUN: candidate=${candidate.config.name}, mode=$accessControl, packages=$packageCount, ipv6=${artifacts.ipv6}")
        val builder = Builder()
            .setBlocking(false)
            .setMtu(TUN_MTU)
            .setSession(getString(R.string.vpn_notification_title))
            .addAddress(TUN_GATEWAY, TUN_SUBNET_PREFIX)
            .addRoute(NET_ANY, 0)
            .addDnsServer(TUN_DNS_PRIMARY)
            .addDnsServer(TUN_DNS_SECONDARY)

        if (artifacts.ipv6) {
            LogManager.logDebug("VPN", "TUN: adding IPv6 route ::/0")
            builder.addRoute("::", 0)
        }

        when (accessControl) {
            info.loveyu.mfca.config.M2mAccessControlMode.acceptAll -> {
                LogManager.logDebug("VPN", "TUN: acceptAll mode, disallowing self ($packageName)")
                runCatching { builder.addDisallowedApplication(packageName) }
            }

            info.loveyu.mfca.config.M2mAccessControlMode.exclude -> {
                val pkgs = (candidate.effectivePackages + packageName).distinct()
                LogManager.logDebug("VPN", "TUN: exclude mode, disallowing ${pkgs.size} apps: ${pkgs.take(5)}${if (pkgs.size > 5) "..." else ""}")
                pkgs.forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }

            info.loveyu.mfca.config.M2mAccessControlMode.include -> {
                LogManager.logDebug("VPN", "TUN: include mode, allowing ${candidate.effectivePackages.size} apps: ${candidate.effectivePackages.take(5)}${if (candidate.effectivePackages.size > 5) "..." else ""}")
                candidate.effectivePackages.distinct().forEach { pkg ->
                    runCatching { builder.addAllowedApplication(pkg) }
                }
            }
        }

        val configureIntent = PendingIntent.getActivity(
            this,
            1202,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.setConfigureIntent(configureIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val tun = builder.establish()
        if (tun == null) {
            LogManager.logError("VPN", "TUN establishment returned null — VPN may not be permitted or supported")
        } else {
            LogManager.logInfo("VPN", "TUN established: fd=${tun.fd}")
        }
        return tun
    }

    private fun closeTunInterface() {
        if (tunInterface != null) {
            LogManager.logInfo("VPN", "Closing TUN interface")
        }
        runCatching { tunInterface?.close() }
        tunInterface = null
    }

    private fun findTunInterfaceName(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && addr.hostAddress == TUN_GATEWAY) {
                        return iface.name
                    }
                }
            }
            null
        } catch (e: Exception) {
            LogManager.logError("VPN", "Failed to find TUN interface: ${e.message}")
            null
        }
    }

    private fun updateNotification() {
        ForwardService.refreshNotification()
        if (!ForwardService.isServiceAlive()) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(ForwardService.NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            ForwardService.NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, ForwardService.CHANNEL_ID)
            .setContentTitle(ForwardService.buildNotificationText())
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

}
