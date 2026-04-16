package info.loveyu.mfca.link

import android.content.Context
import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.link.tcp.TcpFrameProtocol
import info.loveyu.mfca.link.tcp.TcpProtocolFactory
import info.loveyu.mfca.util.CertResolver
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 *   automaticReconnect (bool, 默认true)
 *   reconnectInterval (秒, 默认10)
 *   reconnectMaxInterval (秒, 默认60, 预留)
 *   protocol (lb/tlv/split, 默认 lb)
 *   maxLength (字节, 默认1048576即1MB, 单条消息最大长度)
 *   split (仅 split 协议, 分隔符, 支持 \n \r \t \0 \\ 转义)
 *
 * 帧协议:
 *   lb   : | length(4, 大端序) | body(N) |              (默认)
 *   tlv  : | type(2) | length(4, 大端序) | body(N) |
 *   split: 分隔符协议
 *
 * 重连模型：与 MQTT/WebSocket 一致
 *   - 无内部 scheduleReconnect，由 LinkManager 健康检查驱动重连
 *   - 连续失败计数器 (MAX=5)，超过后等待健康检查 resetFailureCount
 *   - 最小重试间隔防止高频重连
 */
class TcpLink(override val config: LinkConfig, private val context: Context) : Link {

    override val id: String = config.id

    private val isSsl = config.dsn?.startsWith("ssl://") == true
    private var socket: Socket? = null
    private var connected = AtomicBoolean(false)
    private var messageListener: ((ByteArray) -> Unit)? = null
    private var errorListener: ((Exception) -> Unit)? = null
    override var maxFailureCallback: (() -> Unit)? = null
    override var recoveredCallback: (() -> Unit)? = null
    @Volatile private var resolvedIp: String? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readerJob: Job? = null

    private var params: Map<String, String> = emptyMap()
    private var autoReconnect = true
    private var retryIntervalMs = DEFAULT_RETRY_INTERVAL_MS
    private var maxReconnectDelay = 60L

    // Reconnection state (MQTT-consistent model)
    private var consecutiveFailures = 0
    private var hadMaxFailure = false
    private var lastConnectAttempt = 0L

    private var maxLength: Int = 1048576
    private var readTimeoutMs: Long = 30_000L   // socket soTimeout
    private var writeTimeoutMs: Long = 10_000L  // coroutine timeout
    private val frameProtocol: TcpFrameProtocol
    private val splitDelimiterHex: String?

    private val host: String
    private val port: Int

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val DEFAULT_RETRY_INTERVAL_MS = 10_000L // 10 seconds
    }

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

        // Reconnect settings: DSN param > config.reconnect > default
        autoReconnect = params["automaticReconnect"]?.toBoolean()
            ?: (config.reconnect?.enabled ?: true)
        retryIntervalMs = params["reconnectInterval"]?.toLongOrNull()?.let { it * 1000 }
            ?: (config.reconnect?.interval?.millis ?: DEFAULT_RETRY_INTERVAL_MS)
        maxReconnectDelay = params["reconnectMaxInterval"]?.toLongOrNull()
            ?: (config.reconnect?.maxInterval?.millis?.let { it / 1000 } ?: 60L)
    }

    // ---- Connection lifecycle ----

    override fun connect(): Boolean {
        if (connected.get()) return true

        // Backoff: too many consecutive failures
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            LogManager.logWarn("TCP", "Skipping connect for $id: too many failures ($consecutiveFailures), waiting for next health check")
            return false
        }

        // Backoff: exponential backoff based on consecutive failures
        val now = System.currentTimeMillis()
        val currentInterval = minOf(
            retryIntervalMs * (1L shl minOf(consecutiveFailures, 10)),
            maxReconnectDelay * 1000
        )
        if (now - lastConnectAttempt < currentInterval) {
            return false
        }
        lastConnectAttempt = now

        try {
            val connectTimeout = params["connectTimeout"]?.toIntOrNull() ?: 10
            val keepAlive = params["keepAlive"]?.toBoolean() ?: true
            val noDelay = params["noDelay"]?.toBoolean() ?: true

            LogManager.logDebug("TCP", "Connecting to $host:$port (protocol=${frameProtocol.name}, ssl=$isSsl)")

            val rawSocket = Socket().apply {
                connect(java.net.InetSocketAddress(host, port), connectTimeout * 1000)
                this.soTimeout = readTimeoutMs.toInt()
                this.keepAlive = keepAlive
                this.tcpNoDelay = noDelay
            }

            socket = if (isSsl) {
                val isInsecure = params["insecure"]?.toBoolean() ?: (config.tls?.insecure ?: false)
                val sslConfig = CertResolver.createSslConfig(config.tls, context, "TCP", isInsecure)
                val factory = sslConfig?.socketFactory
                    ?: (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                factory.createSocket(rawSocket, host, port, true) as Socket
            } else {
                rawSocket
            }

            connected.set(true)
            LinkManager.notifyLinkStateChanged(id, connected = true)
            val shouldNotify = hadMaxFailure && consecutiveFailures > 0
            consecutiveFailures = 0
            hadMaxFailure = false
            resolvedIp = (socket?.remoteSocketAddress as? java.net.InetSocketAddress)?.address?.hostAddress
            LogManager.logInfo("TCP", "Connected: $id (${frameProtocol.name})")

            if (shouldNotify) {
                recoveredCallback?.invoke()
            }

            startReading()
            return true
        } catch (e: Exception) {
            consecutiveFailures++
            if (consecutiveFailures == MAX_CONSECUTIVE_FAILURES) {
                hadMaxFailure = true
                maxFailureCallback?.invoke()
            }
            LogManager.logError("TCP", "Connection error ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES): ${e.message}")
            errorListener?.invoke(e)
            connected.set(false)
            return false
        }
    }

    override fun disconnect() {
        consecutiveFailures = 0
        readerJob?.cancel()
        try {
            connected.set(false)
            socket?.close()
            socket = null
            LinkManager.notifyLinkStateChanged(id, connected = false)
            LogManager.logDebug("TCP", "Disconnected: $id")
        } catch (e: Exception) {
            LogManager.logWarn("TCP", "Disconnect error: ${e.message}")
        }
    }

    override fun isConnected(): Boolean = connected.get()

    override fun shouldAutoReconnect(): Boolean = autoReconnect

    override fun resetFailureCount() {
        consecutiveFailures = 0
    }

    // ---- Send ----

    override fun send(data: ByteArray): Boolean {
        if (!connected.get() || socket == null) {
            LogManager.logDebug("TCP", "Cannot send: not connected")
            return false
        }
        return try {
            val framed = frameProtocol.frame(data)
            val os = socket?.outputStream ?: return false
            // 直接阻塞写入（调用方已在 worker 线程），避免 runBlocking 开销
            os.write(framed)
            os.flush()
            LogManager.logDebug("TCP", "Sent frame: ${frameProtocol.name}, size=${framed.size}")
            true
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
                    LogManager.logDebug("TCP", "Server closed connection")
                    connected.set(false)
                }
            } catch (e: SocketTimeoutException) {
                LogManager.logWarn("TCP", "Read timeout (${readTimeoutMs}ms), disconnecting")
                disconnect()
            } catch (e: EOFException) {
                LogManager.logDebug("TCP", "Server closed connection")
                connected.set(false)
            } catch (e: SocketException) {
                if (connected.get()) {
                    LogManager.logError("TCP", "Read error: ${e.message}")
                    errorListener?.invoke(e)
                    connected.set(false)
                }
            } catch (e: Exception) {
                if (connected.get()) {
                    LogManager.logError("TCP", "Read error: ${e.message}")
                    errorListener?.invoke(e)
                    connected.set(false)
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
