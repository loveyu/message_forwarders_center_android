package info.loveyu.mfca.link

import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP 连接实现
 * Broker URL 格式: tcp://host:port[?param1=value1&...]
 * URL 参数支持：
 *   connectTimeout (秒), soTimeout (秒)
 *   keepAlive (true/false), noDelay (true/false)
 *   automaticReconnect, reconnectInterval, reconnectMaxInterval (秒)
 */
class TcpLink(override val config: LinkConfig) : Link {

    override val id: String = config.id

    private var socket: Socket? = null
    private var connected = AtomicBoolean(false)
    private var messageListener: ((ByteArray) -> Unit)? = null
    private var errorListener: ((Exception) -> Unit)? = null
    override var reconnectCallback: (() -> Boolean)? = null
    @Volatile private var resolvedIp: String? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readerJob: Job? = null
    private var reconnectJob: Job? = null

    private var params: Map<String, String> = emptyMap()
    private var autoReconnect = true
    private var reconnectDelay = 5L
    private var maxReconnectDelay = 60L

    // Resolved host/port from config or broker URL
    private val host: String
    private val port: Int

    init {
        // Validate DSN protocol is tcp or ssl
        val type = LinkType.fromDsn(config.dsn)
        if (type != LinkType.tcp) {
            throw IllegalArgumentException("TcpLink requires tcp:// or ssl:// DSN")
        }

        // Parse dsn URL if provided, otherwise use host/port
        val dsn = config.dsn
        if (dsn != null) {
            val (cleanDsn, parsedParams) = parseUrlParams(dsn)
            params = parsedParams
            val uri = URI(cleanDsn)
            host = uri.host ?: "127.0.0.1"
            port = uri.port ?: 6000
        } else {
            host = config.host ?: "127.0.0.1"
            port = config.port ?: 6000
        }

        // Reconnect settings from params or config
        autoReconnect = params["automaticReconnect"]?.toBoolean()
            ?: (config.reconnect?.enabled ?: true)
        reconnectDelay = params["reconnectInterval"]?.toLongOrNull()
            ?: config.reconnect?.interval?.timeUnit?.toSeconds(config.reconnect?.interval?.millis ?: 5000) ?: 5L
        maxReconnectDelay = params["reconnectMaxInterval"]?.toLongOrNull()
            ?: config.reconnect?.maxInterval?.timeUnit?.toSeconds(config.reconnect?.maxInterval?.millis ?: 60000) ?: 60L
    }

    override fun connect(): Boolean {
        if (connected.get()) return true

        try {
            val connectTimeout = params["connectTimeout"]?.toIntOrNull() ?: 10
            val soTimeout = params["soTimeout"]?.toIntOrNull() ?: 30
            val keepAlive = params["keepAlive"]?.toBoolean() ?: true
            val noDelay = params["noDelay"]?.toBoolean() ?: true

            LogManager.appendLog("TCP", "Connecting to $host:$port")

            socket = Socket().apply {
                connect(java.net.InetSocketAddress(host, port), connectTimeout * 1000)
                this.soTimeout = soTimeout * 1000
                this.keepAlive = keepAlive
                this.tcpNoDelay = noDelay
            }

            connected.set(true)
            reconnectJob?.cancel()
            // Cache resolved IP
            resolvedIp = (socket?.remoteSocketAddress as? java.net.InetSocketAddress)?.address?.hostAddress
            LogManager.appendLog("TCP", "Connected: $id")

            startReading()
            return true
        } catch (e: Exception) {
            LogManager.appendLog("TCP", "Connection error: ${e.message}")
            errorListener?.invoke(e)
            connected.set(false)
            scheduleReconnect()
            return false
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnect) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            kotlinx.coroutines.delay(reconnectDelay * 1000)
            LogManager.appendLog("TCP", "Attempting reconnect: $id")
            reconnectCallback?.invoke() ?: connect()
        }
    }

    override fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
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
                            scheduleReconnect()
                            break
                        }
                    } catch (e: SocketException) {
                        if (connected.get()) {
                            LogManager.appendLog("TCP", "Read error: ${e.message}")
                            errorListener?.invoke(e)
                            scheduleReconnect()
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                if (connected.get()) {
                    LogManager.appendLog("TCP", "Read error: ${e.message}")
                    errorListener?.invoke(e)
                    scheduleReconnect()
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

    override fun getConnectionDetails(): Map<String, String> {
        val details = mutableMapOf<String, String>()
        details["protocol"] = if (config.dsn?.startsWith("ssl://") == true) "SSL/TCP" else "TCP"
        details["host"] = host
        details["port"] = port.toString()
        resolvedIp?.let { if (it != host) details["resolved_ip"] = it }
        return details
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
