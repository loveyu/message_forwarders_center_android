package info.loveyu.mfca.link

import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.util.LogManager
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * WebSocket 连接实现
 */
class WebSocketLink(override val config: LinkConfig) : Link {

    override val id: String = config.id

    private var webSocket: WebSocket? = null
    private var connected = false
    private var messageListener: ((ByteArray) -> Unit)? = null
    private var errorListener: ((Exception) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    init {
        if (config.type != LinkType.websocket) {
            throw IllegalArgumentException("WebSocketLink requires websocket type config")
        }
    }

    override fun connect(): Boolean {
        if (connected) return true

        val urlStr = config.url ?: return false

        LogManager.appendLog("WS", "Connecting to $urlStr")

        val request = Request.Builder()
            .url(urlStr)
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                LogManager.appendLog("WS", "Connected: $id")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                messageListener?.invoke(text.toByteArray())
                LogManager.appendLog("WS", "Message received: ${text.take(100)}")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                messageListener?.invoke(bytes.toByteArray())
                LogManager.appendLog("WS", "Message received: ${bytes.size} bytes")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                LogManager.appendLog("WS", "Closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                LogManager.appendLog("WS", "Closed: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                LogManager.appendLog("WS", "Error: ${t.message}")
                errorListener?.invoke(t as? Exception ?: Exception(t.message))
            }
        })

        webSocket = ws
        return true // Connection is async, we'll know via callback
    }

    override fun disconnect() {
        webSocket?.close(1000, "Disconnect requested")
        webSocket = null
        connected = false
        LogManager.appendLog("WS", "Disconnected: $id")
    }

    override fun isConnected(): Boolean = connected

    override fun send(data: ByteArray): Boolean {
        if (!connected) {
            LogManager.appendLog("WS", "Cannot send: not connected")
            return false
        }

        val result = webSocket?.send(ByteString.of(*data))
        return result == true
    }

    override fun send(text: String): Boolean {
        if (!connected) {
            LogManager.appendLog("WS", "Cannot send: not connected")
            return false
        }

        val result = webSocket?.send(text)
        return result == true
    }

    override fun setOnMessageListener(listener: (ByteArray) -> Unit) {
        messageListener = listener
    }

    override fun setOnErrorListener(listener: (Exception) -> Unit) {
        errorListener = listener
    }
}
