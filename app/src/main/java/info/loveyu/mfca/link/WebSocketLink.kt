package info.loveyu.mfca.link

import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * WebSocket 连接实现
 * URL 参数支持：
 *   readTimeout, writeTimeout, pingInterval (秒)
 *   automaticReconnect, reconnectInterval, reconnectMaxInterval (秒)
 *   username, password (认证)
 */
class WebSocketLink(override val config: LinkConfig) : Link {

    override val id: String = config.id

    private var webSocket: WebSocket? = null
    private var connected = false
    private var messageListener: ((ByteArray) -> Unit)? = null
    private var errorListener: ((Exception) -> Unit)? = null

    private var params: Map<String, String> = emptyMap()
    private var autoReconnect = true
    private var reconnectDelay = 5L
    private var maxReconnectDelay = 30L
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    init {
        if (config.type != LinkType.websocket) {
            throw IllegalArgumentException("WebSocketLink requires websocket type config")
        }
    }

    override fun connect(): Boolean {
        if (connected) return true

        val urlStr = config.url ?: return false

        // Parse URL params
        val (cleanUrl, parsedParams) = parseUrlParams(urlStr)
        params = parsedParams

        // Reconnect settings
        autoReconnect = params["automaticReconnect"]?.toBoolean()
            ?: (config.reconnect?.enabled ?: true)
        reconnectDelay = params["reconnectInterval"]?.toLongOrNull()
            ?: config.reconnect?.interval?.timeUnit?.toSeconds(config.reconnect?.interval?.millis ?: 3000) ?: 5L
        maxReconnectDelay = params["reconnectMaxInterval"]?.toLongOrNull()
            ?: config.reconnect?.maxInterval?.timeUnit?.toSeconds(config.reconnect?.maxInterval?.millis ?: 30000) ?: 30L

        LogManager.appendLog("WS", "Connecting to $cleanUrl")

        val client = buildOkHttpClient()

        val requestBuilder = Request.Builder().url(cleanUrl)

        // Basic auth from params
        params["username"]?.let { user ->
            params["password"]?.let { pass ->
                val credentials = okhttp3.Credentials.basic(user, pass)
                requestBuilder.addHeader("Authorization", credentials)
            }
        }

        val request = requestBuilder.build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                reconnectJob?.cancel()
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
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                LogManager.appendLog("WS", "Error: ${t.message}")
                errorListener?.invoke(t as? Exception ?: Exception(t.message))
                scheduleReconnect()
            }
        })

        webSocket = ws
        return true
    }

    private fun buildOkHttpClient(): OkHttpClient {
        val readTimeout = params["readTimeout"]?.toLongOrNull() ?: 30L
        val writeTimeout = params["writeTimeout"]?.toLongOrNull() ?: 30L
        val pingInterval = params["pingInterval"]?.toLongOrNull() ?: 30L

        return OkHttpClient.Builder()
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .writeTimeout(writeTimeout, TimeUnit.SECONDS)
            .pingInterval(pingInterval, TimeUnit.SECONDS)
            .build()
    }

    private fun scheduleReconnect() {
        if (!autoReconnect) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            kotlinx.coroutines.delay(reconnectDelay * 1000)
            LogManager.appendLog("WS", "Attempting reconnect: $id")
            connect()
        }
    }

    override fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
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

    private fun parseUrlParams(url: String): Pair<String, Map<String, String>> {
        val params = mutableMapOf<String, String>()
        val queryStart = url.indexOf('?')
        if (queryStart == -1) {
            return Pair(url, params)
        }
        val queryString = url.substring(queryStart + 1)
        val cleanUrl = url.substring(0, queryStart)
        queryString.split('&').forEach { param ->
            val kv = param.split('=', limit = 2)
            if (kv.size == 2) {
                val key = URLDecoder.decode(kv[0], "UTF-8")
                val value = URLDecoder.decode(kv[1], "UTF-8")
                params[key] = value
            }
        }
        return Pair(cleanUrl, params)
    }
}
