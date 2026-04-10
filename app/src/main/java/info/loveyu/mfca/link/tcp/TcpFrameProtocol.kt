package info.loveyu.mfca.link.tcp

/**
 * TCP 帧协议接口。
 *
 * 每种具体协议实现如何将数据打包写入和从流中解帧读取。
 */
interface TcpFrameProtocol {

    /** 协议名称 (lb / tlv / split) */
    val name: String

    /**
     * 将应用层数据打包为线缆帧。
     * @param data 应用层数据（lb/split: body; tlv: type(2)+body）
     * @return 带帧头的完整字节数组
     */
    fun frame(data: ByteArray): ByteArray

    /**
     * 从输入流中循环读取帧，每读到一条完整消息即回调 [onMessage]。
     * 如果遇到无效长度或超长数据则回调 [onInvalid]，由调用方决定是否断开。
     *
     * @param input   底层 InputStream
     * @param maxLength 单条消息最大允许长度
     * @param onMessage 收到一条完整消息
     * @param onInvalid 协议非法（长度无效等）
     */
    suspend fun readLoop(
        input: java.io.InputStream,
        maxLength: Int,
        onMessage: (ByteArray) -> Unit,
        onInvalid: (String) -> Unit
    )
}
