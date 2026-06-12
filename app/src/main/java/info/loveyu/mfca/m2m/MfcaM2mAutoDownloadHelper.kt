package info.loveyu.mfca.m2m

import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.NetworkChecker

internal fun MfcaM2mService.autoDownloadFirstMatch() {
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
        MfcaM2mNotificationHelper.updateNotification(this)

        val result = M2mConfigCacheManager.downloadConfig(this, candidate.config)
        result.onSuccess { M2mManager.selectCandidate(candidate.config.name) }
        result.onFailure { error ->
            LogManager.logWarn("VPN", "Auto-download failed for ${candidate.config.name}: ${error.message}")
        }
        return
    }
}
