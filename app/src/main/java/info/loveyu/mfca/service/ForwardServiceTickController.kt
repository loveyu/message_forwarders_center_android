package info.loveyu.mfca.service

import info.loveyu.mfca.input.InputManager
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.queue.QueueManager
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.m2m.M2mManager
import info.loveyu.mfca.m2m.M2mRuntimeStatus
import info.loveyu.mfca.m2m.MfcaM2mService
import androidx.core.content.ContextCompat
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class ForwardServiceTickController(
    private val service: ForwardService,
    private val appScheduler: ScheduledExecutorService
) {
    companion object {
        const val FAILURE_RESET_TICK_INTERVAL = 20
        const val MIN_TICK_INTERVAL_MS = 5_000L
        private const val NOTIFICATION_PRESENCE_CHECK_INTERVAL_MS = 10 * 60 * 1000L
    }

    private val tickScheduler = CoalescingTicker(
        scheduler = appScheduler,
        onTick = { onTick() },
        onError = { e -> LogManager.logError("SERVICE", "Tick execution error: ${e.message}") }
    )
    private var earlyTickFuture: ScheduledFuture<*>? = null
    var tickCount = 0

    @Volatile
    var tickIntervalMs: Long = 40_000L

    @Volatile
    var lastTickTime: Long = 0L

    @Volatile
    var lastNotificationPresenceCheckMs: Long = 0L

    @Volatile
    var isCharging = false

    @Volatile
    var normalTickIntervalMs: Long = 40_000L

    @Volatile
    var chargingTickIntervalMs: Long = 40_000L

    fun start() {
        cancelEarlyTick()
        tickScheduler.start(tickIntervalMs)
    }

    fun stop() {
        tickScheduler.stop()
        cancelEarlyTick()
    }

    fun cancelEarlyTick() {
        earlyTickFuture?.cancel(false)
        earlyTickFuture = null
    }

    private fun scheduleEarlyTick(delayMs: Long?) {
        cancelEarlyTick()
        if (!ForwardService.isRunning || delayMs == null) return
        val boundedDelayMs = delayMs.coerceAtLeast(1L)
        if (boundedDelayMs >= tickIntervalMs) return
        earlyTickFuture = appScheduler.schedule({
            tickScheduler.request()
        }, boundedDelayMs, TimeUnit.MILLISECONDS)
    }

    fun onTick() {
        cancelEarlyTick()
        if (!ForwardService.isRunning) return
        val now = System.currentTimeMillis()
        lastTickTime = now
        tickCount++
        LogManager.logDebug("SERVICE", "Tick #$tickCount start")

        val nextLinkTickDelayMs = LinkManager.onTick()
        val vpnConfigChanged = M2mManager.onTick(service)
        M2mManager.refresh()
        if (M2mManager.state.value.isEnabled) {
            if (vpnConfigChanged) {
                ContextCompat.startForegroundService(service, MfcaM2mService.refreshIntent(service, forceRestart = true))
            } else {
                MfcaM2mService.sync(service)
            }
            MfcaM2mService.tryApplyPendingLogLevel()
        }
        if (LogManager.isDebugEnabled() && M2mManager.state.value.runtimeStatus == M2mRuntimeStatus.running) {
            M2mManager.logTrafficStats()
        }

        InputManager.onTick()
        QueueManager.onTick()

        if (tickCount % FAILURE_RESET_TICK_INTERVAL == 0) {
            LinkManager.onFailureResetTick()
        }

        if (now - lastNotificationPresenceCheckMs >= NOTIFICATION_PRESENCE_CHECK_INTERVAL_MS) {
            service.checkNotificationPresenceNow("periodic_10m")
            service.invalidateNotificationStatsCache()
            service.updateNotification()
        }

        LogManager.logDebug("SERVICE", "Tick #$tickCount end, flushing logs")
        LogManager.flush()
        OutputManager.flushAllFileOutputs()

        scheduleEarlyTick(nextLinkTickDelayMs)
    }

    fun doTriggerTick() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTickTime
        if (elapsed < MIN_TICK_INTERVAL_MS) {
            LogManager.logDebug("SERVICE", "TriggerTick debounced: ${elapsed}ms < ${MIN_TICK_INTERVAL_MS}ms")
            return
        }
        LogManager.logDebug("SERVICE", "TriggerTick: event-driven early tick (last was ${elapsed}ms ago)")
        tickScheduler.request()
    }

    fun updateChargingState(charging: Boolean) {
        val oldInterval = tickIntervalMs
        isCharging = charging
        tickIntervalMs = if (charging) chargingTickIntervalMs else normalTickIntervalMs
        if (tickIntervalMs != oldInterval) {
            LogManager.logDebug("SERVICE", "Tick interval changed: ${oldInterval}ms → ${tickIntervalMs}ms (charging=$charging)")
            start()
        }
        doTriggerTick()
    }

    fun updateConfigIntervals(
        normalIntervalMs: Long,
        chargingIntervalMs: Long
    ) {
        normalTickIntervalMs = normalIntervalMs
        chargingTickIntervalMs = chargingIntervalMs
        val newInterval = if (isCharging) chargingTickIntervalMs else normalTickIntervalMs
        if (newInterval != tickIntervalMs) {
            tickIntervalMs = newInterval
            start()
            LogManager.logDebug("CONFIG", "Tick interval updated to ${newInterval}ms (charging=$isCharging)")
        }
    }
}
