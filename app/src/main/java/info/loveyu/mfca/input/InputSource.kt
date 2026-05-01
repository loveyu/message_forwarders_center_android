package info.loveyu.mfca.input

/**
 * 输入源接口
 */
interface InputSource {
    val inputName: String
    val inputType: InputType

    fun start()
    fun stop()
    fun isRunning(): Boolean

    fun setOnMessageListener(listener: (InputMessage) -> Unit)

    fun getError(): String? = null

    fun hasFatalError(): Boolean = false
}

enum class InputType {
    http, mqtt, websocket, tcp, failQueue
}

/**
 * 输入消息
 */
data class InputMessage(
    val source: String,
    val data: ByteArray,
    val headers: Map<String, String> = emptyMap()
) {
    val text: String
        get() = String(data)

    val textOrNull: String?
        get() = try { String(data) } catch (e: Exception) { null }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as InputMessage
        return source == other.source && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = source.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
