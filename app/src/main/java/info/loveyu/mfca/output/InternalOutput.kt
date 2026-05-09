package info.loveyu.mfca.output

import android.content.Context
import info.loveyu.mfca.config.InternalOutputConfig
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.NetworkChecker

/**
 * 内部输出接口
 */
sealed interface InternalOutput : Output {
    override val name: String
    val internalContext: Context
    val internalConfig: InternalOutputConfig
    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?)
    override fun isAvailable(): Boolean =
        NetworkChecker.shouldEnable(internalContext, internalConfig.whenCondition, internalConfig.deny)
}