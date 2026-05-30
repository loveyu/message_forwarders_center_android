package info.loveyu.mfca.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import info.loveyu.mfca.MainActivity
import info.loveyu.mfca.R
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MfcaVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var tunInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENABLE -> {
                startForeground(NOTIFICATION_ID, buildNotification("Preparing VPN"))
                serviceScope.launch {
                    VpnManager.setEnabled(true)
                    syncRuntime(forceRestart = intent.getBooleanExtra(EXTRA_FORCE_RESTART, false))
                }
            }

            ACTION_REFRESH -> {
                startForeground(NOTIFICATION_ID, buildNotification(VpnManager.state.value.statusMessage.ifBlank { "Refreshing VPN" }))
                serviceScope.launch {
                    syncRuntime(forceRestart = intent.getBooleanExtra(EXTRA_FORCE_RESTART, false))
                }
            }

            ACTION_DISABLE -> {
                serviceScope.launch {
                    stopRuntime(disableVpn = true, stopService = true)
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        serviceScope.launch {
            stopRuntime(disableVpn = true, stopService = true)
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunInterface()
        VpnBridgeProcessManager.stop()
        MihomoProcessManager.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun syncRuntime(forceRestart: Boolean) {
        if (prepare(this) != null) {
            VpnManager.clearRunningCandidate(VpnRuntimeStatus.error, getString(R.string.vpn_permission_required))
            updateNotification(VpnManager.state.value.statusMessage)
            return
        }

        val selected = VpnManager.getSelectedCandidate()
        if (selected == null) {
            VpnBridgeProcessManager.stop()
            closeTunInterface()
            MihomoProcessManager.stop()
            VpnManager.clearRunningCandidate(VpnRuntimeStatus.error, "当前没有可用 VPN 候选")
            updateNotification(VpnManager.state.value.statusMessage)
            return
        }

        val current = MihomoProcessManager.current()
        val currentBridge = VpnBridgeProcessManager.current()
        if (!forceRestart && current?.candidateName == selected.config.name && currentBridge?.candidateName == selected.config.name) {
            val message = "VPN 运行中: ${selected.config.name}"
            VpnManager.markRunning(selected.config.name, message)
            updateNotification(message)
            return
        }

        if (current != null || currentBridge != null || tunInterface != null) {
            VpnManager.updateRuntimeStatus(VpnRuntimeStatus.stopping, "Stopping ${selected.config.name}")
            updateNotification(VpnManager.state.value.statusMessage)
            stopRuntime(disableVpn = false, stopService = false)
        }

        val artifacts = VpnManager.prepareSelectedCandidate(this).getOrElse { error ->
            VpnManager.clearRunningCandidate(VpnRuntimeStatus.error, error.message ?: "Failed to prepare VPN")
            updateNotification(VpnManager.state.value.statusMessage)
            return
        }

        VpnManager.updateRuntimeStatus(VpnRuntimeStatus.starting, "Starting ${artifacts.candidate.name}")
        updateNotification(VpnManager.state.value.statusMessage)

        val runningCore = MihomoProcessManager.start(
            context = this,
            artifacts = artifacts,
            onUnexpectedExit = { exitCode, tail ->
                val message = "Mihomo exited ($exitCode): $tail"
                LogManager.logError("VPN", message)
                VpnBridgeProcessManager.stop()
                closeTunInterface()
                VpnManager.clearRunningCandidate(VpnRuntimeStatus.error, message)
                updateNotification(message)
            },
        ).getOrElse { error ->
            LogManager.logError("VPN", "Failed to start mihomo: ${error.message}")
            VpnManager.clearRunningCandidate(VpnRuntimeStatus.error, error.message ?: "Failed to start mihomo")
            updateNotification(VpnManager.state.value.statusMessage)
            return
        }

        val tun = establishTun(selected) ?: run {
            MihomoProcessManager.stop()
            VpnManager.clearRunningCandidate(VpnRuntimeStatus.error, getString(R.string.vpn_establish_failed))
            updateNotification(VpnManager.state.value.statusMessage)
            return
        }
        tunInterface = tun

        val runningBridge = VpnBridgeProcessManager.start(
            context = this,
            artifacts = artifacts,
            tunInterface = tun,
            onUnexpectedExit = { exitCode, tail ->
                val message = "VPN bridge exited ($exitCode): $tail"
                LogManager.logError("VPN", message)
                closeTunInterface()
                MihomoProcessManager.stop()
                VpnManager.clearRunningCandidate(VpnRuntimeStatus.error, message)
                updateNotification(message)
            },
        ).getOrElse { error ->
            LogManager.logError("VPN", "Failed to start bridge: ${error.message}")
            closeTunInterface()
            MihomoProcessManager.stop()
            VpnManager.clearRunningCandidate(VpnRuntimeStatus.error, error.message ?: "Failed to start VPN bridge")
            updateNotification(VpnManager.state.value.statusMessage)
            return
        }

        val message = "VPN 运行中: ${runningBridge.candidateName}"
        VpnManager.markRunning(runningCore.candidateName, message)
        updateNotification(message)
    }

    private fun stopRuntime(disableVpn: Boolean, stopService: Boolean) {
        val bridgeStopped = VpnBridgeProcessManager.stop()
        closeTunInterface()
        val stopped = MihomoProcessManager.stop() ?: bridgeStopped
        if (disableVpn) {
            VpnManager.setEnabled(false)
        } else {
            VpnManager.clearRunningCandidate(
                status = VpnRuntimeStatus.idle,
                message = if (stopped == null) "VPN 已停止" else "Stopped $stopped",
            )
        }
        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification(VpnManager.state.value.statusMessage)
        }
    }

    private fun establishTun(candidate: info.loveyu.mfca.vpn.VpnCandidateState): ParcelFileDescriptor? {
        val builder = Builder()
            .setBlocking(false)
            .setMtu(TUN_MTU)
            .setSession(getString(R.string.vpn_notification_title))
            .addAddress(TUN_GATEWAY, TUN_SUBNET_PREFIX)
            .addRoute(NET_ANY, 0)
            .addDnsServer(TUN_DNS_PRIMARY)
            .addDnsServer(TUN_DNS_SECONDARY)

        when (candidate.effectiveAccessControlMode) {
            info.loveyu.mfca.config.VpnAccessControlMode.acceptAll -> {
                runCatching { builder.addDisallowedApplication(packageName) }
            }

            info.loveyu.mfca.config.VpnAccessControlMode.exclude -> {
                (candidate.effectivePackages + packageName).distinct().forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }

            info.loveyu.mfca.config.VpnAccessControlMode.include -> {
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

        return builder.establish()
    }

    private fun closeTunInterface() {
        runCatching { tunInterface?.close() }
        tunInterface = null
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "mfca_vpn_runtime"
        private const val NOTIFICATION_ID = 1202
        const val TUN_MTU = 1500
        const val TUN_SUBNET_PREFIX = 30
        const val TUN_GATEWAY = "172.19.0.1"
        const val TUN_PORTAL = "172.19.0.2"
        const val TUN_GATEWAY_CIDR = "$TUN_GATEWAY/$TUN_SUBNET_PREFIX"
        const val TUN_DNS_PRIMARY = "1.1.1.1"
        const val TUN_DNS_SECONDARY = "8.8.8.8"
        const val NET_ANY = "0.0.0.0"

        const val ACTION_ENABLE = "info.loveyu.mfca.action.ENABLE_VPN"
        const val ACTION_REFRESH = "info.loveyu.mfca.action.REFRESH_VPN"
        const val ACTION_DISABLE = "info.loveyu.mfca.action.DISABLE_VPN"
        private const val EXTRA_FORCE_RESTART = "force_restart"

        fun enableIntent(context: Context): Intent = Intent(context, MfcaVpnService::class.java).apply {
            action = ACTION_ENABLE
        }

        fun refreshIntent(context: Context, forceRestart: Boolean = false): Intent =
            Intent(context, MfcaVpnService::class.java).apply {
                action = ACTION_REFRESH
                putExtra(EXTRA_FORCE_RESTART, forceRestart)
            }

        fun disableIntent(context: Context): Intent = Intent(context, MfcaVpnService::class.java).apply {
            action = ACTION_DISABLE
        }

        fun sync(context: Context, forceRestart: Boolean = false) {
            ContextCompat.startForegroundService(context, refreshIntent(context, forceRestart))
        }
    }
}
