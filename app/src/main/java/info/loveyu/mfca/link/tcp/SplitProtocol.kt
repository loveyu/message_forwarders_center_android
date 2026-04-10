package info.loveyu.mfca.link.tcp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Split 协议: 以指定分隔符切割消息。
 *
 * DSN 参数:
 *   split   - 分隔符字符串，支持 \n \r \t \0 \\ 转义
 *   maxLength - 单条消息最大长度，超长则判定无效
 */
class SplitProtocol(
    private val delimiter: ByteArray
) : TcpFrameProtocol {

    override val name: String = "split"

    override fun frame(data: ByteArray): ByteArray = data + delimiter

    override suspend fun readLoop(
        input: InputStream,
        maxLength: Int,
        onMessage: (ByteArray) -> Unit,
        onInvalid: (String) -> Unit
    ) {
        val delimLen = delimiter.size
        val buffer = ByteArrayOutputStream()
        val readBuf = ByteArray(4096)
        var matchPos = 0

        while (currentCoroutineContext().isActive) {
            val bytesRead = input.read(readBuf)
            if (bytesRead == -1) return // stream closed

            for (i in 0 until bytesRead) {
                val b = readBuf[i]

                if (b == delimiter[matchPos]) {
                    matchPos++
                    if (matchPos == delimLen) {
                        // Complete delimiter found
                        val data = buffer.toByteArray()
                        if (data.isNotEmpty()) {
                            onMessage(data)
                        }
                        buffer.reset()
                        matchPos = 0
                    }
                } else {
                    // Flush partial match + current byte
                    if (matchPos > 0) {
                        buffer.write(delimiter, 0, matchPos)
                        matchPos = 0
                        // Re-check current byte against delimiter start
                        if (b == delimiter[0]) {
                            matchPos = 1
                            if (delimLen == 1) {
                                val d = buffer.toByteArray()
                                if (d.isNotEmpty()) onMessage(d)
                                buffer.reset()
                                matchPos = 0
                            }
                            continue
                        }
                    }
                    buffer.write(b.toInt())
                }

                if (buffer.size() > maxLength) {
                    onInvalid("Split frame exceeds maxLength=$maxLength")
                    return
                }
            }
        }
    }
}
