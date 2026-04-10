package info.loveyu.mfca.link.tcp

/**
 * 根据参数创建对应的 TCP 帧协议实例。
 */
object TcpProtocolFactory {

    /**
     * @param protocolStr "lb" | "tlv" | "split"，null 视为 "lb"
     * @param splitDelimiter 仅 split 协议需要，分隔符字符串（支持 \n \r \t \0 \\ 转义）
     */
    fun create(protocolStr: String?, splitDelimiter: String?): TcpFrameProtocol {
        return when (protocolStr) {
            "tlv" -> TlvProtocol()
            "split" -> SplitProtocol(parseDelimiter(splitDelimiter ?: "\n"))
            else -> LbProtocol()
        }
    }

    /**
     * 解析分隔符字符串，支持转义序列。
     */
    fun parseDelimiter(s: String): ByteArray {
        val result = mutableListOf<Byte>()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { result.add('\n'.code.toByte()); i += 2 }
                    'r' -> { result.add('\r'.code.toByte()); i += 2 }
                    't' -> { result.add('\t'.code.toByte()); i += 2 }
                    '0' -> { result.add(0); i += 2 }
                    '\\' -> { result.add('\\'.code.toByte()); i += 2 }
                    else -> { result.add(s[i].code.toByte()); i++ }
                }
            } else {
                result.add(s[i].code.toByte())
                i++
            }
        }
        return result.toByteArray()
    }
}
