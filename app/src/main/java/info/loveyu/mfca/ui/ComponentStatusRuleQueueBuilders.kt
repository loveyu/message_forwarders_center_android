package info.loveyu.mfca.ui

import android.content.Context
import info.loveyu.mfca.config.PipelineStep
import info.loveyu.mfca.config.RuleConfig
import info.loveyu.mfca.queue.Queue
import info.loveyu.mfca.queue.QueueType
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.NetworkChecker

internal fun formatPipelineStep(step: PipelineStep): String {
    val lines = mutableListOf<String>()
    step.transform?.extract?.let { lines.add("Extract: $it") }
    step.transform?.filter?.let { lines.add("Filter: $it") }
    step.transform?.detect?.let { lines.add("Detect: $it") }
    step.transform?.format?.let { lines.add("Format: $it") }
    step.transform?.enrich?.let { lines.add("Enrich: $it") }
    if (step.to.isNotEmpty()) {
        lines.add("To: ${step.to.joinToString()}")
    }
    return if (lines.isEmpty()) "No transform" else lines.joinToString("\n")
}

internal fun buildRuleStatus(
    context: Context,
    config: RuleConfig
): ComponentStatus {
    val enableResult =
        NetworkChecker.getEnableReason(context, config.whenCondition, config.deny)
    val sources = if (config.froms.isNotEmpty()) config.froms else listOf(config.from)
    val outputs = config.pipeline.flatMap { it.to }.distinct()
    val errorOutputs = config.onError.orEmpty().flatMap { it.to }.distinct()
    val details = buildString {
        append("From: ${sources.joinToString()}")
        append("\nSteps: ${config.pipeline.size}")
        if (outputs.isNotEmpty()) {
            append("\nOutputs: ${outputs.joinToString()}")
        }
        config.pipeline.forEachIndexed { index, step ->
            append("\n\nStep ${index + 1}:")
            append("\n${formatPipelineStep(step)}")
        }
        if (config.onError != null) {
            append("\n\nOn Error Steps: ${config.onError.size}")
            if (errorOutputs.isNotEmpty()) {
                append("\nOn Error Outputs: ${errorOutputs.joinToString()}")
            }
            config.onError.forEachIndexed { index, step ->
                append("\n\nOn Error ${index + 1}:")
                append("\n${formatPipelineStep(step)}")
            }
        }
        if (config.whenCondition != null || config.deny != null) {
            append(
                "\n\n${NetworkChecker.getMatchedConditions(context, config.whenCondition, config.deny)}"
            )
        }
    }
    return ComponentStatus(
        id = config.name,
        name = config.name,
        type = ComponentType.RULE,
        isEnabled = enableResult.enabled,
        isRunning = ForwardService.isRunning && enableResult.enabled,
        notEnabledReason = enableResult.reason,
        details = details
    )
}

internal fun buildQueueStatus(queue: Queue): ComponentStatus {
    val typeStr = when (queue.type) {
        QueueType.memory -> "Memory"
        QueueType.sqlite -> "SQLite"
    }
    val size = try {
        queue.size()
    } catch (_: Exception) {
        -1
    }
    val details = buildString {
        append("Type: $typeStr Queue")
        if (size >= 0) append("\nPending: $size item(s)")
        else append("\nSize: N/A")
    }
    return ComponentStatus(
        id = "queue:${queue.name}",
        name = queue.name,
        type = ComponentType.QUEUE,
        isEnabled = true,
        isRunning = true,
        details = details
    )
}
