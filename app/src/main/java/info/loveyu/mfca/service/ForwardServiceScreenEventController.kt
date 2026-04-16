package info.loveyu.mfca.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import info.loveyu.mfca.output.ClipboardOutput
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.util.LogManager
import java.util.concurrent.ScheduledExecutorService

internal class ForwardServiceScreenEventController(
    private val service: ForwardService,
    private val scheduler: ScheduledExecutorService
) {
    private var screenOnReceiver: BroadcastReceiver? = null

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
                        ClipboardOutput.notifyScreenOn()
                        onNotificationCheck("screen_on")
                        onTriggerTick()
                        flushClipboardOutputs()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        LogManager.logDebug("SERVICE", "User present (unlocked), triggering tick and flushing clipboard")
                        ClipboardOutput.notifyScreenOn()
                        onNotificationCheck("user_present")
                        onTriggerTick()
                        flushClipboardOutputs()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        LogManager.logDebug("SERVICE", "Screen off, clipboard outputs will buffer")
                        ClipboardOutput.notifyScreenOff()
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
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        service.registerReceiver(screenOnReceiver, filter)

        val bm = service.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        onInitialChargingDetected(bm.isCharging)
    }

    fun unregister() {
        screenOnReceiver?.let {
            try {
                service.unregisterReceiver(it)
            } catch (_: Exception) {
            }
            screenOnReceiver = null
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
