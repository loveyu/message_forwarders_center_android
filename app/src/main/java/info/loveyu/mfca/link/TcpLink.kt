package info.loveyu.mfca.link

import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.link.tcp.TcpFrameProtocol
import info.loveyu.mfca.link.tcp.TcpProtocolFactory
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP 连接实现 — 帧协议委托给 [TcpFrameProtocol]。
 *
 * DSN 格式 (必填): tcp://host:port[?param1=value1&...]
 * URL 参数支持：
 *   connectTimeout (秒, 默认10) — 连接超时
 *   readTimeout    (秒, 默认30) — 单次读取超时, 超时断开
 *   writeTimeout   (秒, 默认10) — 单次写入超时, 超时断开
 *   keepAlive (true/false), noDelay (true/false)
 *   automaticReconnect, reconnectInterval, reconnectMaxInterval (秒)
 *   protocol (lb/tlv/split, 默认 lb)
 *   maxLength (字节, 默认1048576即1MB, 单条消息最大长度)
 *   split (仅 split 协议, 分隔符, 支持 \n \r \t \0 \\ 转义)
 *
 * 帧协议:
 *   lb   : | length(4, 大端序) | body(N) |              (默认)
 *   tlv  : | type(2) | length(4, 大端序) | body(N) |
 *   split: 分隔符协议
 *
 * 无效长度、超长数据、读写超时将直接断开连接。
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

    private var maxLength: Int = 1048576
    private var readTimeoutMs: Long = 30_000L   // socket soTimeout
    private var writeTimeoutMs: Long = 10_000L  // coroutine timeout
    private val frameProtocol: TcpFrameProtocol
    private val splitDelimiterHex: String?

    private val host: String
    private val port: Int

    init {
        val type = LinkType.fromDsn(config.dsn)
        if (type != LinkType.tcp) {
            throw IllegalArgumentException("TcpLink requires tcp:// or ssl:// DSN")
        }

        val dsn = config.dsn
            ?: throw IllegalArgumentException("TcpLink requires DSN (tcp://host:port)")

        val (cleanDsn, parsedParams) = parseUrlParams(dsn)
        params = parsedParams
        val uri = URI(cleanDsn)
        host = uri.host ?: "127.0.0.1"
        port = uri.port ?: 6000

        // Timeouts
        readTimeoutMs = (params["readTimeout"]?.toLongOrNull() ?: 30) * 1000
        writeTimeoutMs = (params["writeTimeout"]?.toLongOrNull() ?: 10) * 1000

        // Protocol
        maxLength = params["maxLength"]?.toIntOrNull() ?: 1048576
        val protocolStr = params["protocol"]
        val splitStr = params["split"]
        frameProtocol = TcpProtocolFactory.create(protocolStr, splitStr)
        splitDelimiterHex = if (protocolStr == "split") {
            TcpProtocolFactory.parseDelimiter(splitStr ?: "\n").joinToString("") { "%02x".format(it) }
        } else null

        // Reconnect
        autoReconnect = params["automaticReconnect"]?.toBoolean()
            ?: (config.reconnect?.enabled ?: true)
        reconnectDelay = params["reconnectInterval"]?.toLongOrNull()
            ?: config.reconnect?.interval?.timeUnit?.toSeconds(config.reconnect?.interval?.millis ?: 5000) ?: 5L
        maxReconnectDelay = params["reconnectMaxInterval"]?.toLongOrNull()
            ?: config.reconnect?.maxInterval?.timeUnit?.toSeconds(config.reconnect?.maxInterval?.millis ?: 60000) ?: 60L
    }

    // ---- Connection lifecycle ----

    override fun connect(): Boolean {
        if (connected.get()) return true

        try {
            val connectTimeout = params["connectTimeout"]?.toIntOrNull() ?: 10
            val keepAlive = params["keepAlive"]?.toBoolean() ?: true
            val noDelay = params["noDelay"]?.toBoolean() ?: true

            LogManager.log("TCP", "Connecting to $host:$port (protocol=${frameProtocol.name})")

            socket = Socket().apply {
                connect(java.net.InetSocketAddress(host, port), connectTimeout * 1000)
                this.soTimeout = readTimeoutMs.toInt()
                this.keepAlive = keepAlive
                this.tcpNoDelay = noDelay
            }

            connected.set(true)
            reconnectJob?.cancel()
            resolvedIp = (socket?.remoteSocketAddress as? java.net.InetSocketAddress)?.address?.hostAddress
            LogManager.log("TCP", "Connected: $id (${frameProtocol.name})")

            startReading()
            return true
        } catch (e: Exception) {
            LogManager.logError("TCP", "Connection error: ${e.message}")
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
            LogManager.log("TCP", "Attempting reconnect: $id")
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
            LogManager.log("TCP", "Disconnected: $id")
        } catch (e: Exception) {
            LogManager.logWarn("TCP", "Disconnect error: ${e.message}")
        }
    }

    override fun isConnected(): Boolean = connected.get()

    // ---- Send ----

    override fun send(data: ByteArray): Boolean {
        if (!connected.get() || socket == null) {
            LogManager.log("TCP", "Cannot send: not connected")
            return false
        }
        return try {
            val framed = frameProtocol.frame(data)
            val success = runBlocking {
                withTimeoutOrNull(writeTimeoutMs) {
                    val os = socket?.outputStream ?: return@withTimeoutOrNull false
                    os.write(framed)
                    os.flush()
                    true
                }
            }
            if (success == null) {
                LogManager.logWarn("TCP", "Write timeout (${writeTimeoutMs}ms), disconnecting")
                disconnect()
                false
            } else {
                LogManager.logDebug("TCP", "Sent frame: ${frameProtocol.name}, size=${framed.size}")
                true
            }
        } catch (e: Exception) {
            LogManager.logError("TCP", "Send error: ${e.message}")
            errorListener?.invoke(e)
            false
        }
    }

    override fun send(text: String): Boolean = send(text.toByteArray())

    // ---- Read ----

    private fun startReading() {
        readerJob = scope.launch {
            try {
                val input = socket?.inputStream ?: return@launch
                frameProtocol.readLoop(
                    input = input,
                    maxLength = maxLength,
                    onMessage = { data ->
                        messageListener?.invoke(data)
                        LogManager.logDebug("TCP", "Received frame: ${frameProtocol.name}, length=${data.size}")
                    },
                    onInvalid = { reason ->
                        LogManager.logWarn("TCP", "$reason, disconnecting")
                        disconnect()
                    }
                )
                // readLoop returned normally — stream EOF
                if (connected.get()) {
                    LogManager.log("TCP", "Server closed connection")
                    connected.set(false)
                    scheduleReconnect()
                }
            } catch (e: SocketTimeoutException) {
                LogManager.logWarn("TCP", "Read timeout (${readTimeoutMs}ms), disconnecting")
                disconnect()
            } catch (e: EOFException) {
                LogManager.log("TCP", "Server closed connection")
                connected.set(false)
                scheduleReconnect()
            } catch (e: SocketException) {
                if (connected.get()) {
                    LogManager.logError("TCP", "Read error: ${e.message}")
                    errorListener?.invoke(e)
                    scheduleReconnect()
                }
            } catch (e: Exception) {
                if (connected.get()) {
                    LogManager.logError("TCP", "Read error: ${e.message}")
                    errorListener?.invoke(e)
                    scheduleReconnect()
                }
            }
        }
    }

    // ---- Listeners ----

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
        details["frameProtocol"] = frameProtocol.name
        details["maxLength"] = maxLength.toString()
        details["readTimeout"] = "${readTimeoutMs}ms"
        details["writeTimeout"] = "${writeTimeoutMs}ms"
        if (frameProtocol.name == "split") {
            splitDelimiterHex?.let { details["split"] = it }
        }
        resolvedIp?.let { if (it != host) details["resolved_ip"] = it }
        return details
    }

    // ---- URL parsing ----

    private fun parseUrlParams(url: String): Pair<String, Map<String, String>> {
        val params = mutableMapOf<String, String>()
        val queryStart = url.indexOf('?')
        if (queryStart == -1) return Pair(url, params)
        val queryString = url.substring(queryStart + 1)
        val cleanUrl = url.substring(0, queryStart)
        queryString.split('&').forEach { param ->
            val kv = param.split('=', limit = 2)
            if (kv.size == 2) {
                params[URLDecoder.decode(kv[0], "UTF-8")] = URLDecoder.decode(kv[1], "UTF-8")
            }
        }
        return Pair(cleanUrl, params)
    }
}
