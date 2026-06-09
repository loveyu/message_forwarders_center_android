package info.loveyu.mfca.test

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import info.loveyu.mfca.MainActivity
import info.loveyu.mfca.R
import info.loveyu.mfca.config.VpnInputConfig
import info.loveyu.mfca.plugin.MihomoPluginCore
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.vpn.MfcaVpnService
import info.loveyu.mfca.vpn.MihomoProcessManager
import info.loveyu.mfca.vpn.PreparedVpnArtifacts
import info.loveyu.mfca.vpn.VpnBridgeProcessManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class M2mVpnTestService : VpnService() {

    sealed class Event {
        data class Log(val message: String) : Event()
        object Ready : Event()
        data class Error(val message: String) : Event()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var tunInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startTestForeground()
                val pluginPath = intent.getStringExtra(EXTRA_PLUGIN_PATH) ?: return START_NOT_STICKY
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH) ?: return START_NOT_STICKY
                val mixedPort = intent.getIntExtra(EXTRA_MIXED_PORT, 2080)
                val includeSelf = intent.getBooleanExtra(EXTRA_INCLUDE_SELF, false)
                serviceScope.launch {
                    startVpn(pluginPath, configPath, mixedPort, includeSelf)
                }
            }
            ACTION_STOP -> {
                stopVpn()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startTestForeground() {
        ensureNotificationChannel()
        val notification = buildNotification("m2m VPN 测试运行中")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun startVpn(pluginPath: String, configPath: String, mixedPort: Int, includeSelf: Boolean) {
        if (prepare(this) != null) {
            emit(Event.Error("VPN 权限未授予"))
            stopSelf()
            return
        }

        val candidate = VpnInputConfig(name = "m2m-test", configUrl = "")
        val artifacts = PreparedVpnArtifacts(
            candidate = candidate,
            coreFilePath = pluginPath,
            profileFilePath = configPath,
            localProxyPort = mixedPort,
        )

        // Start mihomo core
        emit(Event.Log("正在启动 m2m 核心代理…"))
        MihomoPluginCore.socketProtector = MihomoPluginCore.SocketProtector { fd -> protect(fd) }
        val runningCore = MihomoProcessManager.start(this, artifacts) { _, tail ->
            LogManager.logError("M2mVpnTest", "Core exited: $tail")
        }.getOrElse { error ->
            emit(Event.Error("核心启动失败: ${error.message}"))
            stopSelf()
            return
        }
        emit(Event.Log("核心代理已启动"))

        // Wait for proxy port ready
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (!runningCore.core.isRunning()) {
                emit(Event.Error("核心代理意外退出"))
                MihomoProcessManager.stop()
                stopSelf()
                return
            }
            if (canConnect(mixedPort)) break
            Thread.sleep(300)
        }
        if (!canConnect(mixedPort)) {
            emit(Event.Error("代理端口就绪超时"))
            MihomoProcessManager.stop()
            stopSelf()
            return
        }
        emit(Event.Log("代理端口 $mixedPort 已就绪"))

        // Establish TUN
        emit(Event.Log("正在建立 TUN 接口 (includeSelf=$includeSelf)…"))
        val tun = establishTun(includeSelf)
        if (tun == null) {
            emit(Event.Error("TUN 接口建立失败"))
            MihomoProcessManager.stop()
            stopSelf()
            return
        }
        tunInterface = tun
        emit(Event.Log("TUN 接口已建立"))

        // Start VPN bridge
        emit(Event.Log("正在启动 VPN 桥接…"))
        VpnBridgeProcessManager.start(this, artifacts, tun) { _, tail ->
            LogManager.logError("M2mVpnTest", "Bridge exited: $tail")
        }.getOrElse { error ->
            emit(Event.Error("VPN 桥接启动失败: ${error.message}"))
            closeTun()
            MihomoProcessManager.stop()
            stopSelf()
            return
        }
        emit(Event.Log("VPN 桥接已启动"))

        Thread.sleep(1000)
        emit(Event.Ready)
    }

    private fun stopVpn() {
        MihomoPluginCore.socketProtector = null
        VpnBridgeProcessManager.stop()
        closeTun()
        MihomoProcessManager.stop()
        try {
            stopForeground(STOP_FOREGROUND_DETACH)
        } catch (_: Exception) {}
    }

    private fun establishTun(includeSelf: Boolean): ParcelFileDescriptor? {
        val builder = Builder()
            .setBlocking(false)
            .setMtu(MfcaVpnService.TUN_MTU)
            .setSession("m2m VPN Test")
            .addAddress(MfcaVpnService.TUN_GATEWAY, MfcaVpnService.TUN_SUBNET_PREFIX)
            .addRoute(MfcaVpnService.NET_ANY, 0)
            .addDnsServer(MfcaVpnService.TUN_DNS_PRIMARY)
            .addDnsServer(MfcaVpnService.TUN_DNS_SECONDARY)

        if (includeSelf) {
            runCatching { builder.addAllowedApplication(packageName) }
        } else {
            runCatching { builder.addDisallowedApplication(packageName) }
        }

        val configureIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.setConfigureIntent(configureIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        return builder.establish()
    }

    private fun closeTun() {
        runCatching { tunInterface?.close() }
        tunInterface = null
    }

    private fun emit(event: Event) {
        val msg = when (event) {
            is Event.Log -> event.message
            is Event.Ready -> "VPN 就绪"
            is Event.Error -> "错误: ${event.message}"
        }
        LogManager.logInfo("M2mVpnTest", msg)
        Handler(Looper.getMainLooper()).post {
            callback?.invoke(event)
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "m2m VPN 测试", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("m2m VPN 测试")
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
    }

    companion object {
        const val ACTION_START = "info.loveyu.mfca.action.M2M_VPN_TEST_START"
        const val ACTION_STOP = "info.loveyu.mfca.action.M2M_VPN_TEST_STOP"
        const val EXTRA_PLUGIN_PATH = "plugin_path"
        const val EXTRA_CONFIG_PATH = "config_path"
        const val EXTRA_MIXED_PORT = "mixed_port"
        const val EXTRA_INCLUDE_SELF = "include_self"
        private const val CHANNEL_ID = "m2m_vpn_test"
        private const val NOTIFICATION_ID = 20001

        var callback: ((Event) -> Unit)? = null
    }
}
