package info.loveyu.mfca.m2m

import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class MfcaM2mRetryHandler(
    private val service: MfcaM2mService,
    private val retryAttemptsMax: Int = 3,
    private val retryDelayMs: Long = 5_000L,
) {
    @Volatile
    var retryAttempts: Int = 0
    private var retryJob: Job? = null

    fun nextRetryReason(candidateName: String, sessionId: Long): String? {
        if (sessionId != service.runtimeSessionId) {
            LogManager.logDebug("VPN", "Retry not allowed for $candidateName: sessionId mismatch ($sessionId != ${service.runtimeSessionId})")
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
        if (retryAttempts >= retryAttemptsMax) {
            LogManager.logDebug("VPN", "Retry not allowed for $candidateName: retry limit reached ($retryAttempts/$retryAttemptsMax)")
            return "retry limit reached"
        }
        return null
    }

    fun scheduleRetry(candidateName: String, message: String) {
        cancelPendingRetry("scheduling next retry")
        retryAttempts += 1
        val retryMessage = service.getString(info.loveyu.mfca.R.string.vpn_retrying, candidateName, retryAttempts, retryAttemptsMax)
        LogManager.logWarn("VPN", "$message. $retryMessage")
        M2mManager.clearRunningCandidate(M2mRuntimeStatus.error, retryMessage)
        MfcaM2mNotificationHelper.updateNotification(service)
        retryJob = service.serviceScope.launch {
            delay(retryDelayMs * retryAttempts)
            val state = M2mManager.state.value
            if (!state.isEnabled || state.activeCandidateName != candidateName) {
                LogManager.logInfo("VPN", "Cancel pending retry for $candidateName because runtime target changed")
                return@launch
            }
            LogManager.logInfo("VPN", "Retrying VPN startup for $candidateName")
            service.syncRuntime(forceRestart = true, isRetry = true)
        }
    }

    fun cancelPendingRetry(reason: String) {
        if (retryJob?.isActive == true) {
            LogManager.logInfo("VPN", "Canceling pending VPN retry: $reason")
        }
        retryJob?.cancel()
        retryJob = null
    }
}
