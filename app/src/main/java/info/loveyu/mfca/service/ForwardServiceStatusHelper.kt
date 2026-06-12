package info.loveyu.mfca.service

import info.loveyu.mfca.config.AppStatusConfig
import info.loveyu.mfca.util.AppStatusManager
import info.loveyu.mfca.util.LogManager

internal fun ForwardService.saveStatus() {
    try {
        val status = AppStatusConfig(
            configUrl = ForwardService.currentConfigUrl,
            isRunning = ForwardService.isRunning,
            isReceivingEnabled = ForwardService.isReceivingEnabled,
            isForwardingEnabled = ForwardService.isForwardingEnabled,
            isWakeLockEnabled = ForwardService.isWakeLockEnabled,
            isWifiLockEnabled = ForwardService.isWifiLockEnabled,
            autoStart = preferences.autoStart,
            appAutoStartOnBoot = preferences.autoStart,
        )
        AppStatusManager.saveStatus(this, status)
    } catch (e: Exception) {
        LogManager.logWarn("APP_STATUS", "Failed to save status: ${e.message}")
    }
}

internal fun ForwardService.loadStatus() {
    try {
        val status = AppStatusManager.loadStatus(this)
        ForwardService.currentConfigUrl = status.configUrl
        ForwardService.isReceivingEnabled = status.isReceivingEnabled
        ForwardService.isForwardingEnabled = status.isForwardingEnabled
        ForwardService.isWakeLockEnabled = status.isWakeLockEnabled
        ForwardService.isWifiLockEnabled = status.isWifiLockEnabled
        preferences.receivingEnabled = status.isReceivingEnabled
        preferences.forwardingEnabled = status.isForwardingEnabled
        preferences.autoStart = status.autoStart
        wasRunningBeforeRestart = status.isRunning
        LogManager.logDebug("APP_STATUS", "Status loaded: running=${status.isRunning}, receive=${status.isReceivingEnabled}, forward=${status.isForwardingEnabled}, wakeLock=${status.isWakeLockEnabled}")
    } catch (e: Exception) {
        LogManager.logWarn("APP_STATUS", "Failed to load status: ${e.message}")
    }
}
