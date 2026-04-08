package info.loveyu.mfca.link

import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP 连接实现
 */
class TcpLink(override val config: LinkConfig) : Link {

    override val id: String = config.id

    private var socket: Socket? = null
    private var connected = AtomicBoolean(false)
    private var messageListener: ((ByteArray) -> Unit)? = null
    private var errorListener: ((Exception) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readerJob: Job? = null

    private val host: String = config.host ?: "127.0.0.1"
    private val port: Int = config.port ?: 6000

    init {
        if (config.type != LinkType.tcp) {
            throw IllegalArgumentException("TcpLink requires tcp type config")
        }
    }

    override fun connect(): Boolean {
        if (connected.get()) return true

        try {
            LogManager.appendLog("TCP", "Connecting to $host:$port")

            socket = Socket(host, port).apply {
                soTimeout = 30000
                keepAlive = true
            }

            connected.set(true)
            LogManager.appendLog("TCP", "Connected: $id")

            startReading()
            return true
        } catch (e: Exception) {
            LogManager.appendLog("TCP", "Connection error: ${e.message}")
            errorListener?.invoke(e)
            connected.set(false)
            return false
        }
    }

    override fun disconnect() {
        try {
            connected.set(false)
            readerJob?.cancel()
            socket?.close()
            socket = null
            LogManager.appendLog("TCP", "Disconnected: $id")
        } catch (e: Exception) {
            LogManager.appendLog("TCP", "Disconnect error: ${e.message}")
        }
    }

    override fun isConnected(): Boolean = connected.get()

    override fun send(data: ByteArray): Boolean {
        if (!connected.get() || socket == null) {
            LogManager.appendLog("TCP", "Cannot send: not connected")
            return false
        }

        return try {
            socket?.outputStream?.write(data)
            socket?.outputStream?.flush()
            LogManager.appendLog("TCP", "Sent ${data.size} bytes")
            true
        } catch (e: Exception) {
            LogManager.appendLog("TCP", "Send error: ${e.message}")
            errorListener?.invoke(e)
            false
        }
    }

    override fun send(text: String): Boolean = send(text.toByteArray())

    private fun startReading() {
        readerJob = scope.launch {
            try {
                val inputStream = socket?.inputStream
                val buffer = ByteArray(4096)

                while (isActive && connected.get()) {
                    try {
                        val bytesRead = inputStream?.read(buffer) ?: -1
                        if (bytesRead > 0) {
                            val data = buffer.copyOf(bytesRead)
                            messageListener?.invoke(data)
                            LogManager.appendLog("TCP", "Received ${bytesRead} bytes")
                        } else if (bytesRead == -1) {
                            LogManager.appendLog("TCP", "Server closed connection")
                            connected.set(false)
                            break
                        }
                    } catch (e: SocketException) {
                        if (connected.get()) {
                            LogManager.appendLog("TCP", "Read error: ${e.message}")
                            errorListener?.invoke(e)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                if (connected.get()) {
                    LogManager.appendLog("TCP", "Read error: ${e.message}")
                    errorListener?.invoke(e)
                }
            }
        }
    }

    override fun setOnMessageListener(listener: (ByteArray) -> Unit) {
        messageListener = listener
    }

    override fun setOnErrorListener(listener: (Exception) -> Unit) {
        errorListener = listener
    }
}
