package info.loveyu.mfca.link

import android.content.Context
import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.util.CertResolver
import info.loveyu.mfca.util.LogManager
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/**
 * WebSocket 连接实现
 *
 * DSN 格式: ws[s]://[username:password@]host:port/path[?param1=value1...]
 *
 * URL 参数支持：
 *   readTimeout, writeTimeout, pingInterval (秒)
 *   automaticReconnect (bool)
 *   reconnectInterval (秒, 默认10)
 *   reconnectMaxInterval (秒, 默认60, 预留)
 *   username, password (Basic 认证)
 *   token (Gotify token 认证)
 *   protocol=gotify (启用 Gotify WebSocket 协议，自动添加 /stream 路径)
 *
 * 重连模型：与 MQTT/TCP 一致
 *   - 无内部 scheduleReconnect，由 LinkManager 健康检查驱动重连
 *   - 连续失败计数器 (MAX=5)，超过后等待健康检查 resetFailureCount
 *   - 最小重试间隔防止高频重连
 */
class WebSocketLink(override val config: LinkConfig) : Link {

    private var isGotifyProtocol = false
    private var context: Context? = null

    override val id: String = config.id

    private var webSocket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var connecting = false
    private var messageListener: ((ByteArray) -> Unit)? = null
    private var errorListener: ((Exception) -> Unit)? = null
    override var maxFailureCallback: (() -> Unit)? = null
    override var recoveredCallback: (() -> Unit)? = null
    private var lastHandshake: Handshake? = null
    @Volatile private var resolvedIp: String? = null
    private val connectionLock = Any()

    private var params: Map<String, String> = emptyMap()
    private var autoReconnect = true
    private var retryIntervalMs = DEFAULT_RETRY_INTERVAL_MS
    private var maxReconnectDelay = 60L

    // Reconnection state (MQTT-consistent model)
    private var consecutiveFailures = 0
    private var hadMaxFailure = false
    private var lastConnectAttempt = 0L
    @Volatile private var peerCertificates: List<X509Certificate>? = null

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val DEFAULT_RETRY_INTERVAL_MS = 10_000L // 10 seconds
    }

    init {
        val type = LinkType.fromDsn(config.dsn)
        if (type != LinkType.websocket) {
            throw IllegalArgumentException("WebSocketLink requires ws:// or wss:// DSN")
        }
    }

    fun setContext(ctx: Context) {
        context = ctx.applicationContext
    }

    override fun connect(): Boolean {
        if (connected) return true
        if (connecting) return false

        // Backoff: too many consecutive failures
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            LogManager.logWarn("WS", "Skipping connect for $id: too many failures ($consecutiveFailures), waiting for next health check")
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

        synchronized(connectionLock) {
            if (connected || connecting) return connected

            // Cleanup previous WebSocket
            try {
                webSocket?.close(1000, null)
            } catch (_: Exception) {}
            webSocket = null
            connecting = true

            val urlStr = config.dsn ?: return false.also { connecting = false }

            // Parse URL params
            val (cleanUrl, parsedParams) = parseUrlParams(urlStr)
            params = parsedParams

            // Check for Gotify protocol
            isGotifyProtocol = params["protocol"] == "gotify"

            // Resolve reconnect settings: DSN param > config.reconnect > default
            autoReconnect = params["automaticReconnect"]?.toBoolean()
                ?: (config.reconnect?.enabled ?: true)
            retryIntervalMs = params["reconnectInterval"]?.toLongOrNull()?.let { it * 1000 }
                ?: (config.reconnect?.interval?.millis ?: DEFAULT_RETRY_INTERVAL_MS)
            maxReconnectDelay = params["reconnectMaxInterval"]?.toLongOrNull()
                ?: (config.reconnect?.maxInterval?.millis?.let { it / 1000 } ?: 60L)

            LogManager.log("WS", "Connecting to $cleanUrl (gotify=$isGotifyProtocol)")

            val client = buildOkHttpClient()

            // Build URL - for Gotify, add /stream path if not present
            val baseUrl = if (isGotifyProtocol && !cleanUrl.contains("/stream")) {
                cleanUrl.trimEnd('/') + "/stream"
            } else {
                cleanUrl
            }

            // For Gotify, token can be in params (extracted from query) - rebuild query with token
            val finalUrl = if (isGotifyProtocol && params["token"] != null) {
                val queryParams = params.filterKeys { it != "protocol" }
                    .map { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
                    .joinToString("&")
                "$baseUrl?$queryParams"
            } else {
                baseUrl
            }

            val requestBuilder = Request.Builder().url(finalUrl)

            // Basic auth from params
            params["username"]?.let { user ->
                params["password"]?.let { pass ->
                    val credentials = okhttp3.Credentials.basic(user, pass)
                    requestBuilder.addHeader("Authorization", credentials)
                }
            }

            LogManager.logDebug("WS", "Final URL: $finalUrl")

            val request = requestBuilder.build()
            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val shouldNotifyRecovered: Boolean
                    synchronized(connectionLock) {
                        connected = true
                        connecting = false
                        shouldNotifyRecovered = hadMaxFailure && consecutiveFailures > 0
                        consecutiveFailures = 0
                        hadMaxFailure = false
                    }
                    lastHandshake = response.handshake
                    if (shouldNotifyRecovered) {
                        recoveredCallback?.invoke()
                    }
                    LogManager.log("WS", "Connected: $id")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    messageListener?.invoke(text.toByteArray())
                    LogManager.logDebug("WS", "Message received: ${text.take(100)}")
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    messageListener?.invoke(bytes.toByteArray())
                    LogManager.logDebug("WS", "Message received: ${bytes.size} bytes")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    LogManager.log("WS", "Closing: $code $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    synchronized(connectionLock) {
                        connected = false
                        connecting = false
                    }
                    LogManager.log("WS", "Closed: $code $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val shouldNotify: Boolean
                    synchronized(connectionLock) {
                        connected = false
                        connecting = false
                        consecutiveFailures++
                        shouldNotify = consecutiveFailures == MAX_CONSECUTIVE_FAILURES
                        if (shouldNotify) hadMaxFailure = true
                    }
                    LogManager.logError("WS", "Error ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES): ${t.message}")
                    if (shouldNotify) {
                        maxFailureCallback?.invoke()
                    }
                    errorListener?.invoke(t as? Exception ?: Exception(t.message))
                }
            })

            webSocket = ws
            return true
        }
    }

    private fun buildOkHttpClient(): OkHttpClient {
        val readTimeout = params["readTimeout"]?.toLongOrNull() ?: 30L
        val writeTimeout = params["writeTimeout"]?.toLongOrNull() ?: 30L
        val pingInterval = params["pingInterval"]?.toLongOrNull() ?: 30L

        val builder = OkHttpClient.Builder()
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .writeTimeout(writeTimeout, TimeUnit.SECONDS)
            .pingInterval(pingInterval, TimeUnit.SECONDS)

        // TLS: wss:// scheme or explicit tls config
        val isWss = config.dsn?.startsWith("wss://") == true
        if (isWss || config.tls != null) {
            val ctx = context
            if (ctx != null) {
                val isInsecure = params["insecure"]?.toBoolean() ?: (config.tls?.insecure ?: false)
                val sslConfig = CertResolver.createSslConfig(config.tls, ctx, "WS", isInsecure) { certs ->
                    peerCertificates = certs
                }
                if (sslConfig != null) {
                    builder.sslSocketFactory(sslConfig.socketFactory, sslConfig.trustManager)
                }
            } else {
                LogManager.logWarn("WS", "No context available for TLS configuration, using system defaults")
            }
        }

        return builder.build()
    }

    override fun disconnect() {
        connecting = false
        consecutiveFailures = 0
        synchronized(connectionLock) {
            try {
                webSocket?.close(1000, "Disconnect requested")
            } catch (_: Exception) {}
            webSocket = null
            connected = false
        }
        LogManager.log("WS", "Disconnected: $id")
    }

    override fun isConnected(): Boolean = connected

    override fun shouldAutoReconnect(): Boolean = autoReconnect

    override fun resetFailureCount() {
        consecutiveFailures = 0
    }

    override fun send(data: ByteArray): Boolean {
        if (!connected) {
            LogManager.logWarn("WS", "Cannot send: not connected")
            return false
        }

        val result = webSocket?.send(ByteString.of(*data))
        if (result != true) {
            LogManager.logWarn("WS", "Binary send failed for $id")
        }
        return result == true
    }

    override fun send(text: String): Boolean {
        if (!connected) {
            LogManager.logWarn("WS", "Cannot send: not connected")
            return false
        }

        val result = webSocket?.send(text)
        if (result != true) {
            LogManager.logWarn("WS", "Text send failed for $id")
        }
        return result == true
    }

    override fun setOnMessageListener(listener: (ByteArray) -> Unit) {
        messageListener = listener
    }

    override fun setOnErrorListener(listener: (Exception) -> Unit) {
        errorListener = listener
    }

    override fun getConnectionDetails(): Map<String, String> {
        val details = mutableMapOf<String, String>()
        val urlStr = config.dsn ?: return details
        details["protocol"] = if (urlStr.startsWith("wss://")) "WSS" else "WS"
        try {
            val uri = URI(urlStr)
            uri.host?.let { h ->
                details["host"] = h
                if (uri.port != -1) details["port"] = uri.port.toString()
                val ip = resolvedIp ?: try {
                    InetAddress.getByName(h).hostAddress?.also { resolvedIp = it }
                } catch (_: Exception) { null }
                if (ip != null && ip != h) details["resolved_ip"] = ip
            }
        } catch (_: Exception) {}
        return details
    }

    override fun getTlsInfo(): TlsConnectionInfo? {
        // Prefer peer certificates captured during TLS handshake
        val certs = peerCertificates
        if (certs != null && certs.isNotEmpty()) {
            val certInfos = certs.map { cert ->
                CertInfo(
                    subject = cert.subjectX500Principal.name,
                    issuer = cert.issuerX500Principal.name,
                    validFrom = cert.notBefore.toString(),
                    validTo = cert.notAfter.toString(),
                    serialNumber = cert.serialNumber.toString(16),
                    fingerprintSha256 = cert.encoded?.let {
                        val md = MessageDigest.getInstance("SHA-256")
                        md.digest(it).joinToString(":") { "%02x".format(it) }
                    }
                )
            }
            return TlsConnectionInfo(
                protocol = "TLS",
                peerCertificates = certInfos
            )
        }

        // Fallback to OkHttp handshake info
        val handshake = lastHandshake ?: return null
        val peerCerts = handshake.peerCertificates.mapNotNull { cert ->
            if (cert is java.security.cert.X509Certificate) {
                CertInfo(
                    subject = cert.subjectX500Principal.name,
                    issuer = cert.issuerX500Principal.name,
                    validFrom = cert.notBefore.toString(),
                    validTo = cert.notAfter.toString(),
                    serialNumber = cert.serialNumber.toString(16),
                    fingerprintSha256 = cert.encoded?.let {
                        val md = MessageDigest.getInstance("SHA-256")
                        md.digest(it).joinToString(":") { "%02x".format(it) }
                    }
                )
            } else null
        }
        return TlsConnectionInfo(
            protocol = handshake.tlsVersion?.javaName,
            cipherSuite = handshake.cipherSuite?.javaName,
            peerCertificates = peerCerts
        )
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
