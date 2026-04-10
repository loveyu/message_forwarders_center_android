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
     * 内部 scheduleReconnect() 应调用此回调而非直接 connect()，
     * 回调内部会检查 when/deny 网络条件。
     */
    var reconnectCallback: (() -> Boolean)?

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
