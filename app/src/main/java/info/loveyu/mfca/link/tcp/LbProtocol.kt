package info.loveyu.mfca.link.tcp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * LB 协议: | length(4, 大端序) | body(N) |
 */
class LbProtocol : TcpFrameProtocol {

    override val name: String = "lb"

    override fun frame(data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + data.size)
        buf.putInt(data.size)
        buf.put(data)
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
            val length = dis.readInt()
            if (length <= 0 || length > maxLength) {
                onInvalid("Invalid lb frame length: $length (max=$maxLength)")
                return
            }
            val body = ByteArray(length)
            dis.readFully(body)
            onMessage(body)
        }
    }
}
