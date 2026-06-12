package info.loveyu.mfca.m2m.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.ParcelFileDescriptor
import android.net.VpnService
import androidx.core.content.ContextCompat
import info.loveyu.mfca.R
import info.loveyu.mfca.m2m.config.M2mConfigCacheManager
import info.loveyu.mfca.m2m.core.M2mBridgeProcessManager
import info.loveyu.mfca.m2m.core.M2mCoreManager
import info.loveyu.mfca.m2m.core.M2mManager
import info.loveyu.mfca.m2m.models.M2mLogLevel
import info.loveyu.mfca.m2m.models.M2mRuntimeStatus
import info.loveyu.mfca.m2m.process.M2mProcessManager
import info.loveyu.mfca.m2m.util.MfcaM2mNotificationHelper
import info.loveyu.mfca.m2m.util.MfcaM2mRetryHandler
import info.loveyu.mfca.m2m.util.MfcaM2mTunHelper
import info.loveyu.mfca.m2m.util.autoDownloadFirstMatch
import info.loveyu.mfca.plugin.M2mPluginCore
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class MfcaM2mService : VpnService() {
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var tunInterface: ParcelFileDescriptor? = null
    @Volatile internal var runtimeSessionId: Long = 0L
    private var physicalNetwork: Network? = null
    internal val retryHandler = MfcaM2mRetryHandler(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "null"
        val forceRestart = intent?.getBooleanExtra(EXTRA_FORCE_RESTART, false) ?: false
        LogManager.logInfo("VPN", "MfcaM2mService onStartCommand: action=$action, forceRestart=$forceRestart, startId=$startId")
        when (action) {
            ACTION_ENABLE -> {
                MfcaM2mNotificationHelper.startVpnForeground(this)
                serviceScope.launch {
                    M2mManager.setEnabled(true)
                    syncRuntime(forceRestart = forceRestart, isRetry = false)
                }
            }

            ACTION_REFRESH -> {
                MfcaM2mNotificationHelper.startVpnForeground(this)
                serviceScope.launch {
                    syncRuntime(forceRestart = forceRestart, isRetry = false)
                }
            }

            ACTION_DISABLE -> {
                MfcaM2mNotificationHelper.startVpnForeground(this)
                serviceScope.launch {
                    stopRuntime(disableVpn = true, stopService = true)
                }
            }

            else -> LogManager.logWarn("VPN", "Unknown action received: $action")
        }
        return START_STICKY
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
        retryHandler.cancelPendingRetry("service destroyed")
        closeTunInterface()
        M2mBridgeProcessManager.stop()
        M2mProcessManager.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    internal suspend fun syncRuntime(forceRestart: Boolean, isRetry: Boolean) {
        val candidateName = M2mManager.getSelectedCandidate()?.config?.name ?: "?"
        LogManager.logInfo("VPN", "syncRuntime: candidate=$candidateName, forceRestart=$forceRestart, isRetry=$isRetry")
        if (!isRetry) {
            retryHandler.cancelPendingRetry("runtime sync requested")
            retryHandler.retryAttempts = 0
        }
        if (M2mManager.state.value.runtimeStatus == M2mRuntimeStatus.preparing) {
            LogManager.logDebug("VPN", "Skipping sync, preparation already in progress")
            return
        }
        if (prepare(this) != null) {
            LogManager.logWarn("VPN", "VPN permission required, launching prepare intent")
            runtimeSessionId = 0L
            M2mManager.clearRunningCandidate(M2mRuntimeStatus.error, getString(R.string.vpn_permission_required))
            MfcaM2mNotificationHelper.updateNotification(this)
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
            runtimeSessionId = 0L
            M2mBridgeProcessManager.stop()
            closeTunInterface()
            M2mProcessManager.stop()
            M2mManager.setEnabled(false)
            M2mManager.clearRunningCandidate(M2mRuntimeStatus.error, "当前没有可用候选")
            MfcaM2mNotificationHelper.updateNotification(this)
            return
        }

        val current = M2mProcessManager.current()
        val currentBridge = M2mBridgeProcessManager.current()
        if (!forceRestart && current?.candidateName == selected.config.name && currentBridge?.candidateName == selected.config.name) {
            val message = "m2m 运行中: ${selected.config.name}"
            LogManager.logInfo("VPN", "Candidate ${selected.config.name} already running, skipping sync")
            M2mManager.markRunning(selected.config.name, message)
            MfcaM2mNotificationHelper.updateNotification(this)
            return
        }

        if (current != null || currentBridge != null || tunInterface != null) {
            LogManager.logInfo("VPN", "Stopping previous runtime (${current?.candidateName}) before starting ${selected.config.name}")
            M2mManager.updateRuntimeStatus(M2mRuntimeStatus.stopping, "Stopping ${selected.config.name}")
            MfcaM2mNotificationHelper.updateNotification(this)
            stopRuntime(disableVpn = false, stopService = false)
        }

        val sessionId = System.nanoTime().also { runtimeSessionId = it }

        val cachedFile = M2mConfigCacheManager.getCachedSourceFile(this, selected.config.name)
        if (cachedFile == null) {
            LogManager.logInfo("VPN", "Config not cached for ${selected.config.name}, downloading from ${selected.config.configUrl}")
            M2mManager.updateRuntimeStatus(M2mRuntimeStatus.preparing, "Downloading config for ${selected.config.name}")
            MfcaM2mNotificationHelper.updateNotification(this)
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
        MfcaM2mNotificationHelper.updateNotification(this)
        LogManager.logDebug("VPN", "Artifacts: port=${artifacts.localProxyPort}, apiPort=${artifacts.apiPort}, udpRelay=${artifacts.udpRelay}, dnsHijack=${artifacts.dnsHijack}, core=${artifacts.coreFilePath}, profile=${artifacts.profileFilePath}")

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        physicalNetwork = cm.activeNetwork
        if (physicalNetwork != null) {
            LogManager.logDebug("VPN", "Physical network cached: ${physicalNetwork}")
        } else {
            LogManager.logWarn("VPN", "No active network available, will use VpnService.protect() fallback")
        }
        M2mPluginCore.socketProtector = M2mPluginCore.SocketProtector { fd ->
            val net = physicalNetwork
            if (net != null) {
                try {
                    ParcelFileDescriptor.fromFd(fd).use { pfd ->
                        net.bindSocket(pfd.fileDescriptor)
                    }
                    return@SocketProtector true
                } catch (e: Exception) {
                    LogManager.logWarn("VPN", "Network.bindSocket failed: ${e.message}, falling back to VpnService.protect()")
                }
            }
            protect(fd)
        }
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

        val ifacesBefore = MfcaM2mTunHelper.snapshotInterfaceNames()
        val tun = MfcaM2mTunHelper.establish(this, selected, artifacts) ?: run {
            M2mProcessManager.stop()
            handleRuntimeFailure(
                candidateName = artifacts.candidate.name,
                sessionId = sessionId,
                message = getString(R.string.vpn_establish_failed),
            )
            return
        }
        tunInterface = tun
        val ifaceName = MfcaM2mTunHelper.findInterfaceName(ifacesBefore)
        LogManager.logInfo("VPN", "Established TUN for ${artifacts.candidate.name}, interface=$ifaceName")
        M2mManager.onTunEstablished(ifaceName)
        M2mManager.onApiReady(artifacts.apiPort, artifacts.apiSecret, serviceScope)
        LogManager.logDebug("VPN", "TUN fd=${tun.fd}, mtu=$TUN_MTU, gateway=$TUN_GATEWAY/$TUN_SUBNET_PREFIX, dns=$TUN_DNS_PRIMARY/$TUN_DNS_SECONDARY, apiPort=${artifacts.apiPort}")

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

        retryHandler.cancelPendingRetry("runtime started")
        retryHandler.retryAttempts = 0
        val message = "m2m 运行中: ${runningBridge.candidateName}"
        M2mManager.markRunning(runningCore.candidateName, message)
        MfcaM2mNotificationHelper.updateNotification(this)
        LogManager.logInfo("VPN", "VPN runtime started for ${runningCore.candidateName}")
    }

    private fun stopRuntime(disableVpn: Boolean, stopService: Boolean) {
        val runningName = M2mManager.state.value.runningCandidateName
        LogManager.logInfo("VPN", "Stopping runtime: candidate=$runningName, disableVpn=$disableVpn, stopService=$stopService")
        retryHandler.cancelPendingRetry("runtime stopping")
        runtimeSessionId = 0L
        M2mManager.onTunDestroyed()
        clearPendingLogLevel()
        M2mConfigCacheManager.cancelDownload()
        M2mCoreManager.cancelDownload()
        M2mPluginCore.socketProtector = null
        val bridgeStopped = M2mBridgeProcessManager.stop()
        closeTunInterface()
        val stopped = M2mProcessManager.stop() ?: bridgeStopped
        if (disableVpn) {
            retryHandler.retryAttempts = 0
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
            MfcaM2mNotificationHelper.updateNotification(this)
        }
    }

    private suspend fun handleUnexpectedRuntimeExit(candidateName: String, sessionId: Long, message: String) {
        handleRuntimeFailure(candidateName, sessionId, message)
    }

    private suspend fun handleRuntimeFailure(candidateName: String, sessionId: Long, message: String) {
        val retryReason = retryHandler.nextRetryReason(candidateName, sessionId)
        LogManager.logError("VPN", "Runtime failure for $candidateName: $message (retryReason=$retryReason)")
        runtimeSessionId = 0L
        M2mPluginCore.socketProtector = null
        M2mBridgeProcessManager.stop()
        closeTunInterface()
        M2mProcessManager.stop()

        if (retryReason == null) {
            LogManager.logWarn("VPN", "Scheduling retry #${retryHandler.retryAttempts + 1} for $candidateName")
            retryHandler.scheduleRetry(candidateName, message)
            return
        }

        retryHandler.retryAttempts = 0
        LogManager.logInfo("VPN", "Skip VPN retry for $candidateName: $retryReason")
        M2mManager.setEnabled(false)
        M2mManager.clearRunningCandidate(M2mRuntimeStatus.error, message)
        MfcaM2mNotificationHelper.updateNotification(this)
    }

    private fun closeTunInterface() {
        MfcaM2mTunHelper.close(tunInterface)
        tunInterface = null
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
}
