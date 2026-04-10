package info.loveyu.mfca.link.tcp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * TLV 协议: | type(2) | length(4, 大端序) | body(N) |
 *
 * - send 时 data 前 2 字节为 type，剩余为 body
 * - 收到后交付 type(2) + body，保持与 send 一致
 */
class TlvProtocol : TcpFrameProtocol {

    override val name: String = "tlv"

    override fun frame(data: ByteArray): ByteArray {
        require(data.size >= 2) { "TLV send requires at least 2 bytes for type" }
        val body = data.copyOfRange(2, data.size)
        val buf = ByteBuffer.allocate(2 + 4 + body.size)
        buf.put(data, 0, 2)         // type
        buf.putInt(body.size)       // length
        buf.put(body)               // body
        return buf.array()
    }

    override suspend fun readLoop(
        input: InputStream,
        maxLength: Int,
        onMessage: (ByteArray) -> Unit,
        onInvalid: (String) -> Unit
    ) {
        val dis = DataInputStream(input)
        while (currentCoroutineContext().isActive) {
            val type = ByteArray(2)
            dis.readFully(type)

            val length = dis.readInt()
            if (length < 0 || length > maxLength) {
                onInvalid("Invalid tlv frame length: $length (max=$maxLength)")
                return
            }

            val body = ByteArray(length)
            dis.readFully(body)

            // Deliver type + body
            val message = type + body
            onMessage(message)
        }
    }
}
