package info.loveyu.mfca.link

import info.loveyu.mfca.config.LinkConfig

/**
 * 连接接口
 */
interface Link {
    val id: String
    val config: LinkConfig

    fun connect(): Boolean
    fun disconnect()
    fun isConnected(): Boolean

    fun send(data: ByteArray): Boolean
    fun send(text: String): Boolean

    fun setOnMessageListener(listener: (ByteArray) -> Unit)
    fun setOnErrorListener(listener: (Exception) -> Unit)
}

/**
 * 连接状态
 */
enum class LinkState {
    disconnected,
    connecting,
    connected,
    error
}
