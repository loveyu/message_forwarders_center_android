package info.loveyu.mfca.service

import info.loveyu.mfca.input.InputMessage
import info.loveyu.mfca.util.LogLevel
import info.loveyu.mfca.util.LogManager

internal fun ForwardService.handleMessage(message: InputMessage) {
    LogManager.log(LogLevel.DEBUG, "FS", "NATIVE handleMessage: source=${message.source}, data=${String(message.data).take(30)}")
    LogManager.log(LogLevel.DEBUG, "TRACE:FS", "handleMessage called: source=${message.source}")
    if (!ForwardService.isReceivingEnabled) {
        LogManager.logDebug("FS", "接收已暂停, 忽略消息: source=${message.source}, data=${String(message.data).take(200)}")
        return
    }

    ForwardService.receivedCount++
    ForwardService.onStatsChanged?.invoke()

    LogManager.logDebug("TRACE:FS", "Calling ruleEngine.process for ${message.source}")
    ruleEngineRef?.process(message)

    if (message.headers.isNotEmpty()) {
        LogManager.logDebug("MESSAGE", "Headers: ${message.headers}")
    }
    LogManager.logDebug("MESSAGE", "Processed: ${message.source} -> ${String(message.data).take(1000)}")
}
