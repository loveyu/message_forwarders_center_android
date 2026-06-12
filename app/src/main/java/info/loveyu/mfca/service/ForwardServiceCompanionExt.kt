package info.loveyu.mfca.service

import info.loveyu.mfca.config.ConfigLoader
import info.loveyu.mfca.input.InputManager
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.network.NetworkChecker
import info.loveyu.mfca.m2m.core.M2mManager
import info.loveyu.mfca.m2m.models.M2mRuntimeStatus

fun ForwardService.Companion.buildNotificationText(): String {
    val m2mState = M2mManager.state.value
    if (ForwardService.isRunning) {
        return buildString {
            append("L${ForwardService.linkCount} I${ForwardService.inputCount} O${ForwardService.outputCount}")
            if (!ForwardService.isReceivingEnabled) append(" | 暂停接收")
            if (!ForwardService.isForwardingEnabled) append(" | 暂停转发")
            if (ForwardService.isWakeLockEnabled) append(" | W锁")
            if (ForwardService.isWifiLockEnabled) append(" | WiFi锁")
            when (m2mState.runtimeStatus) {
                M2mRuntimeStatus.running -> append(" | m2m")
                M2mRuntimeStatus.disabled -> { }
                else -> if (m2mState.statusMessage.isNotBlank()) append(" | ${m2mState.statusMessage}")
            }
        }
    }
    return if (m2mState.runtimeStatus != M2mRuntimeStatus.disabled && m2mState.statusMessage.isNotBlank()) {
        m2mState.statusMessage
    } else {
        "已停止"
    }
}

fun ForwardService.Companion.refreshStats() {
    val config = ForwardService.currentConfig
    val ctx = ForwardService.serviceInstance ?: return
    if (config != null) {
        ForwardService.linkCount = config.links.count { link ->
            NetworkChecker.shouldEnable(ctx, link.whenCondition, link.deny)
        }
        ForwardService.inputCount = config.inputs.http.count { input ->
            NetworkChecker.shouldEnable(ctx, input.whenCondition, input.deny)
        } + config.inputs.link.count { input ->
            NetworkChecker.shouldEnable(ctx, input.whenCondition, input.deny)
        } + config.inputs.udp2raw.count { input ->
            input.enabled && NetworkChecker.shouldEnable(ctx, input.whenCondition, input.deny)
        } + config.inputs.m2m.count { input ->
            input.enabled && NetworkChecker.shouldEnable(ctx, input.whenCondition, input.deny)
        }
        ForwardService.outputCount = config.outputs.http.size + config.outputs.internal.size +
            config.outputs.link.count { output ->
                NetworkChecker.shouldEnable(ctx, output.whenCondition, output.deny)
            }
    } else {
        ForwardService.linkCount = LinkManager.getAllLinks().size
        ForwardService.inputCount = InputManager.getAllInputs().size
        ForwardService.outputCount = OutputManager.getAllOutputs().size
    }
    ForwardService.onStatsChanged?.invoke()
    ForwardService.serviceInstance?.updateNotification()
}

fun ForwardService.Companion.loadConfig(yamlContent: String, configUrl: String = ""): Boolean {
    return try {
        val config = ConfigLoader.loadConfig(yamlContent)
        ForwardService.currentConfigUrl = configUrl
        ForwardService.serviceInstance?.applyConfig(config)
        true
    } catch (e: Exception) {
        LogManager.logWarn("CONFIG", "Failed to load config: ${e.message}")
        false
    }
}

fun ForwardService.Companion.updateStatus(configUrl: String) {
    ForwardService.serviceInstance?.saveStatus()
}
