package info.loveyu.mfca.output

import info.loveyu.mfca.config.OutputFormatStep
import info.loveyu.mfca.queue.QueueItem

/**
 * 输出目标接口
 */
interface Output {
    val name: String
    val type: OutputType

    /** 输出前的数据/header 格式化步骤（null 表示不处理） */
    val formatSteps: List<OutputFormatStep>?
        get() = null

    fun send(item: QueueItem, callback: ((Boolean) -> Unit)? = null)
    fun isAvailable(): Boolean
}

/**
 * 输出类型
 */
enum class OutputType {
    http, mqtt, websocket, tcp, internal
}

/**
 * 输出结果
 */
sealed class OutputResult {
    data class Success(val responseCode: Int = 200) : OutputResult()
    data class Failure(val error: String, val exception: Exception? = null) : OutputResult()
    object Unavailable : OutputResult()
}
