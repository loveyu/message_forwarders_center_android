package info.loveyu.mfca.output

import info.loveyu.mfca.config.QueueRefConfig
import info.loveyu.mfca.queue.QueueItem

/**
 * Fan-out output that delivers the same message to multiple link outputs independently.
 * Created by OutputManager when a link output config specifies multiple linkIds.
 *
 * If sub-outputs have their own queueRef, RuleEngine will iterate sub-targets directly
 * (via the FanOut interface) so each upstream is queued independently.
 */
class MultiOutput(
    override val name: String,
    private val subOutputs: List<Output>,
    override val queueRef: info.loveyu.mfca.config.QueueRefConfig? = null
) : Output, FanOut {

    override val type: OutputType
        get() = subOutputs.firstOrNull()?.type ?: OutputType.mqtt

    override val formatSteps
        get() = subOutputs.firstOrNull()?.formatSteps

    override fun subTargets(): List<Output> = subOutputs

    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        subOutputs.forEach { it.send(item, null) }
        callback?.invoke(true)
    }

    override fun isAvailable(): Boolean = subOutputs.any { it.isAvailable() }
}
