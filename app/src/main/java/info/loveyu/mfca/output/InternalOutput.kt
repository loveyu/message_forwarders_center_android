package info.loveyu.mfca.output

import info.loveyu.mfca.queue.QueueItem

/**
 * 内部输出接口
 */
sealed interface InternalOutput : Output {
    override val name: String
    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?)
    override fun isAvailable(): Boolean = true
}