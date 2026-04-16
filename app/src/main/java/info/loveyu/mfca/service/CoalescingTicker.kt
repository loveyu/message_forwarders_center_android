package info.loveyu.mfca.service

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Coalescing ticker runner:
 * - periodic trigger via [start]
 * - event trigger via [request]
 * - if a tick is running, additional triggers are coalesced into at most one pending tick
 */
internal class CoalescingTicker(
    private val scheduler: ScheduledExecutorService,
    private val onTick: () -> Unit,
    private val onError: (Exception) -> Unit
) {
    private var tickFuture: ScheduledFuture<*>? = null
    private val tickLock = Any()
    @Volatile
    private var tickInProgress = false
    @Volatile
    private var tickPending = false

    fun start(intervalMs: Long) {
        tickFuture?.cancel(false)
        tickFuture = scheduler.scheduleAtFixedRate({
            request()
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    fun request() {
        synchronized(tickLock) {
            if (tickInProgress) {
                tickPending = true
                return
            }
            tickInProgress = true
        }
        scheduler.execute { runWorker() }
    }

    fun stop() {
        tickFuture?.cancel(false)
    }

    private fun runWorker() {
        while (true) {
            try {
                onTick()
            } catch (e: Exception) {
                onError(e)
            }

            synchronized(tickLock) {
                if (!tickPending) {
                    tickInProgress = false
                    return
                }
                tickPending = false
            }
        }
    }
}
