package info.loveyu.mfca.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import info.loveyu.mfca.util.LogManager
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class ForwardServiceLockController(
    private val service: ForwardService,
    private val scheduler: ScheduledExecutorService
) {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLockTimeoutFuture: ScheduledFuture<*>? = null
    private var wifiLockTimeoutFuture: ScheduledFuture<*>? = null

    fun acquireLocks(
        wakeEnabled: Boolean,
        wifiEnabled: Boolean,
        wakeTimeoutMs: Long,
        wifiTimeoutMs: Long,
        onWakeAutoRelease: () -> Unit,
        onWifiAutoRelease: () -> Unit
    ) {
        if (wakeEnabled) acquireWakeLock(wakeTimeoutMs, onWakeAutoRelease)
        if (wifiEnabled) acquireWifiLock(wifiTimeoutMs, onWifiAutoRelease)
    }

    fun acquireWakeLock(wakeTimeoutMs: Long, onWakeAutoRelease: () -> Unit) {
        if (wakeLock == null) {
            val powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "mfca::forward_service"
            ).apply {
                acquire()
            }
            LogManager.log("SERVICE", "WakeLock acquired (timeout=${if (wakeTimeoutMs > 0) "${wakeTimeoutMs / 1000}s" else "permanent"})")
        }
        scheduleWakeLockTimeout(wakeTimeoutMs, onWakeAutoRelease)
    }

    fun acquireWifiLock(wifiTimeoutMs: Long, onWifiAutoRelease: () -> Unit) {
        if (wifiLock == null) {
            val wifiManager = service.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(wifiMode, "mfca::forward_service").apply {
                acquire()
            }
            LogManager.log("SERVICE", "WifiLock acquired (timeout=${if (wifiTimeoutMs > 0) "${wifiTimeoutMs / 1000}s" else "permanent"})")
        }
        scheduleWifiLockTimeout(wifiTimeoutMs, onWifiAutoRelease)
    }

    fun releaseWakeLock() {
        wakeLockTimeoutFuture?.cancel(false)
        wakeLockTimeoutFuture = null
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                LogManager.log("SERVICE", "WakeLock released")
            }
        }
        wakeLock = null
    }

    fun releaseWifiLock() {
        wifiLockTimeoutFuture?.cancel(false)
        wifiLockTimeoutFuture = null
        wifiLock?.let {
            if (it.isHeld) {
                it.release()
                LogManager.log("SERVICE", "WifiLock released")
            }
        }
        wifiLock = null
    }

    fun releaseAll() {
        releaseWakeLock()
        releaseWifiLock()
    }

    private fun scheduleWakeLockTimeout(wakeTimeoutMs: Long, onWakeAutoRelease: () -> Unit) {
        wakeLockTimeoutFuture?.cancel(false)
        wakeLockTimeoutFuture = null
        if (wakeTimeoutMs <= 0) return
        wakeLockTimeoutFuture = scheduler.schedule({
            if (wakeLock?.isHeld == true) {
                releaseWakeLock()
                onWakeAutoRelease()
            }
        }, wakeTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun scheduleWifiLockTimeout(wifiTimeoutMs: Long, onWifiAutoRelease: () -> Unit) {
        wifiLockTimeoutFuture?.cancel(false)
        wifiLockTimeoutFuture = null
        if (wifiTimeoutMs <= 0) return
        wifiLockTimeoutFuture = scheduler.schedule({
            if (wifiLock?.isHeld == true) {
                releaseWifiLock()
                onWifiAutoRelease()
            }
        }, wifiTimeoutMs, TimeUnit.MILLISECONDS)
    }
}
