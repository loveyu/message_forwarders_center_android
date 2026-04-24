package info.loveyu.mfca.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.PowerManager
import info.loveyu.mfca.output.ClipboardOutput
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.ScreenStateTracker
import java.util.concurrent.ScheduledExecutorService

internal class ForwardServiceScreenEventController(
    private val service: ForwardService,
    private val scheduler: ScheduledExecutorService
) {
    private var screenOnReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun register(
        onInitialChargingDetected: (Boolean) -> Unit,
        onChargingChanged: (Boolean) -> Unit,
        onTriggerTick: () -> Unit,
        onNotificationCheck: (String) -> Unit
    ) {
        if (screenOnReceiver != null) return
        screenOnReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        LogManager.logDebug("SERVICE", "Screen on, triggering tick and flushing clipboard")
                        ScreenStateTracker.markScreenOn()
                        ClipboardOutput.notifyScreenOn()
                        onNotificationCheck("screen_on")
                        onTriggerTick()
                        flushClipboardOutputs()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        LogManager.logDebug("SERVICE", "User present (unlocked), triggering tick and flushing clipboard")
                        ScreenStateTracker.markScreenOn()
                        ClipboardOutput.notifyScreenOn()
                        onNotificationCheck("user_present")
                        onTriggerTick()
                        flushClipboardOutputs()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        LogManager.logDebug("SERVICE", "Screen off, clipboard outputs will buffer")
                        ScreenStateTracker.markScreenOff()
                        ClipboardOutput.notifyScreenOff()
                        onTriggerTick()
                    }
                    Intent.ACTION_POWER_CONNECTED -> {
                        LogManager.logDebug("SERVICE", "Power connected, switching to charging interval")
                        onChargingChanged(true)
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        LogManager.logDebug("SERVICE", "Power disconnected, switching to normal interval")
                        onChargingChanged(false)
                    }
                }
            }
        }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        service.registerReceiver(screenOnReceiver, filter)

        val bm = service.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isInteractive) {
            ScreenStateTracker.markScreenOn()
            ClipboardOutput.notifyScreenOn()
        } else {
            ScreenStateTracker.markScreenOff()
            ClipboardOutput.notifyScreenOff()
        }
        onInitialChargingDetected(bm.isCharging)

        registerNetworkCallback(onTriggerTick)
    }

    fun unregister() {
        screenOnReceiver?.let {
            try {
                service.unregisterReceiver(it)
            } catch (_: Exception) {}
            screenOnReceiver = null
        }
        unregisterNetworkCallback()
    }

    /**
     * 监听网络可用 / 断开事件，在网络恢复时立即触发 tick，
     * 使 MQTT / WebSocket / TCP 链路尽快重连。
     */
    private fun registerNetworkCallback(onTriggerTick: () -> Unit) {
        try {
            val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        LogManager.logDebug("SERVICE", "Network available, triggering tick for reconnect")
                        onTriggerTick()
                    }

                    override fun onLost(network: Network) {
                        LogManager.logDebug("SERVICE", "Network lost, triggering tick")
                        onTriggerTick()
                    }
                }
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            networkCallback = callback
        } catch (e: Exception) {
            LogManager.logWarn("SERVICE", "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { callback ->
            try {
                val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }

    private fun flushClipboardOutputs() {
        scheduler.execute {
            try {
                OutputManager.flushAllClipboardOutputs()
            } catch (e: Exception) {
                LogManager.logError("SERVICE", "Failed to flush clipboard outputs: ${e.message}")
            }
        }
    }
}
