package info.loveyu.mfca.link

import info.loveyu.mfca.config.LinkConfig

/**
 * 连接接口
 */
interface Link {
    val id: String
    val config: LinkConfig

    /**
     * 条件感知重连回调，由 LinkManager 设置。
     * 回调内部会检查 when/deny 网络条件。
     */
    var reconnectCallback: (() -> Boolean)?

    /**
     * 连续失败达到上限时的回调，由 LinkManager 设置。
     */
    var maxFailureCallback: (() -> Unit)?

    /**
     * 从失败状态恢复（连接成功且之前有失败记录）时的回调，由 LinkManager 设置。
     */
    var recoveredCallback: (() -> Unit)?

    fun connect(): Boolean
    fun disconnect()
    fun isConnected(): Boolean

    fun send(data: ByteArray): Boolean
    fun send(text: String): Boolean

    fun setOnMessageListener(listener: (ByteArray) -> Unit)
    fun setOnErrorListener(listener: (Exception) -> Unit)

    /**
     * 返回连接详情（协议、主机、端口、已解析IP等）
     */
    fun getConnectionDetails(): Map<String, String> = emptyMap()

    /**
     * 返回 TLS 连接信息，非 TLS 连接返回 null
     */
    fun getTlsInfo(): TlsConnectionInfo? = null

    /**
     * 是否允许自动重连，由 LinkManager 健康检查调用。
     * 返回 false 时 LinkManager 不会尝试重连此链接。
     * 默认 true。
     */
    fun shouldAutoReconnect(): Boolean = true

    /**
     * 重置连续失败计数，由 LinkManager 在每个健康检查周期调用。
     * 使链接在达到最大失败次数后仍可在下一个周期重试。
     */
    fun resetFailureCount() {}
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

/**
 * TLS 连接信息
 */
data class TlsConnectionInfo(
    val protocol: String? = null,
    val cipherSuite: String? = null,
    val peerCertificates: List<CertInfo> = emptyList()
)

/**
 * 证书信息
 */
data class CertInfo(
    val subject: String,
    val issuer: String,
    val validFrom: String,
    val validTo: String,
    val serialNumber: String? = null,
    val fingerprintSha256: String? = null
)
