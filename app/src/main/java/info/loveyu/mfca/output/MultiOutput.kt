package info.loveyu.mfca.output

import info.loveyu.mfca.config.QueueRefConfig
import info.loveyu.mfca.queue.QueueItem

/**
 * Fan-out output that delivers the same message to multiple link outputs independently.
 * Created by OutputManager when a link output config specifies multiple linkIds.
 */
class MultiOutput(
    override val name: String,
    private val subOutputs: List<Output>,
    override val queueRef: QueueRefConfig? = null
) : Output {

    override val type: OutputType
        get() = subOutputs.firstOrNull()?.type ?: OutputType.mqtt

    override val formatSteps
        get() = subOutputs.firstOrNull()?.formatSteps

    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        subOutputs.forEach { it.send(item, null) }
        callback?.invoke(true)
    }

    override fun isAvailable(): Boolean = subOutputs.any { it.isAvailable() }
}
