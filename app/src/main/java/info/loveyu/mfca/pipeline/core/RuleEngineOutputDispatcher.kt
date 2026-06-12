package info.loveyu.mfca.pipeline.core

import info.loveyu.mfca.output.FanOut
import info.loveyu.mfca.output.Output
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.output.plugin.OutputPluginDispatcher
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.queue.QueueManager
import info.loveyu.mfca.util.LogManager

internal class RuleEngineOutputDispatcher(
    private val dispatcherLookup: ((String) -> OutputPluginDispatcher?)? = null,
) {
    fun dispatchToOutput(
        output: Output,
        outputName: String,
        outData: ByteArray,
        outHeaders: Map<String, String>,
        ruleName: String,
        source: String,
        onForwarded: (() -> Unit)?,
        isDeadLetter: Boolean = false
    ) {
        // ★ Output plugin intercept
        val pluginDispatcher = dispatcherLookup?.invoke(outputName)
        val (data, headers) = if (pluginDispatcher != null) {
            pluginDispatcher.intercept(
                outputName = outputName,
                outputTypeName = output.type.name,
                data = outData,
                headers = outHeaders,
                ruleName = ruleName,
                source = source,
            )
        } else outData to outHeaders

        val queueRef = if (isDeadLetter) null else output.queueRef

        if (output is FanOut && queueRef == null) {
            val subTargets = output.subTargets()
            if (subTargets.any { it.queueRef != null && !isDeadLetter }) {
                subTargets.forEach { subTarget ->
                    val subQueueRef = if (isDeadLetter) null else subTarget.queueRef
                    if (subQueueRef != null) {
                        val queue = QueueManager.getQueue(subQueueRef.name)
                        if (queue != null) {
                            val queueItem = QueueItem(
                                data = data,
                                metadata = mapOf("rule" to ruleName, "source" to source, "outputName" to subTarget.name),
                                headers = headers,
                                nextAttemptAt = System.currentTimeMillis() + subQueueRef.delay.millis,
                                isDeadLetter = isDeadLetter
                            )
                            if (queue.enqueue(queueItem)) {
                                LogManager.logDebug("RULE", "Rule [$ruleName] -> ${subTarget.name}: enqueued to ${subQueueRef.name}")
                                onForwarded?.invoke()
                            } else {
                                LogManager.logWarn("RULE", "Rule [$ruleName] -> ${subTarget.name}: failed to enqueue to ${subQueueRef.name}")
                            }
                        } else {
                            LogManager.logWarn("RULE", "Rule [$ruleName] -> ${subTarget.name}: queue not found: ${subQueueRef.name}")
                        }
                    } else {
                        val item = QueueItem(
                            data = data,
                            metadata = mapOf("rule" to ruleName, "source" to source, "outputName" to subTarget.name),
                            headers = headers,
                            isDeadLetter = isDeadLetter
                        )
                        subTarget.send(item) { success ->
                            if (success) {
                                LogManager.logDebug("RULE", "Rule [$ruleName] -> ${subTarget.name}: OK")
                                onForwarded?.invoke()
                            } else {
                                LogManager.logWarn("RULE", "Rule [$ruleName] -> ${subTarget.name}: FAILED")
                            }
                        }
                    }
                }
                return
            }
        }

        if (queueRef != null) {
            val queue = QueueManager.getQueue(queueRef.name)
            if (queue != null) {
                val queueItem = QueueItem(
                    data = data,
                    metadata = mapOf("rule" to ruleName, "source" to source, "outputName" to outputName),
                    headers = headers,
                    nextAttemptAt = System.currentTimeMillis() + queueRef.delay.millis,
                    isDeadLetter = isDeadLetter
                )
                if (queue.enqueue(queueItem)) {
                    LogManager.logDebug("RULE", "Rule [$ruleName] -> $outputName: enqueued to ${queueRef.name}")
                    onForwarded?.invoke()
                } else {
                    LogManager.logWarn("RULE", "Rule [$ruleName] -> $outputName: failed to enqueue to ${queueRef.name}")
                }
            } else {
                LogManager.logWarn("RULE", "Rule [$ruleName] -> $outputName: queue not found: ${queueRef.name}")
            }
        } else {
            val item = QueueItem(
                data = data,
                metadata = mapOf("rule" to ruleName, "source" to source, "outputName" to outputName),
                headers = headers,
                isDeadLetter = isDeadLetter
            )
            output.send(item) { success ->
                if (success) {
                    LogManager.logDebug("RULE", "Rule [$ruleName] -> $outputName: OK")
                    onForwarded?.invoke()
                } else {
                    LogManager.logWarn("RULE", "Rule [$ruleName] -> $outputName: FAILED")
                }
            }
        }
    }
}
