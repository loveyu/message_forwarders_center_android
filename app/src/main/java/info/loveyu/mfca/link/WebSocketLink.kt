package info.loveyu.mfca.link

import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.launch
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
import java.util.concurrent.TimeUnit

/**
 * WebSocket 连接实现
 * URL 参数支持：
 *   readTimeout, writeTimeout, pingInterval (秒)
 *   automaticReconnect, reconnectInterval, reconnectMaxInterval (秒)
 *   username, password (Basic 认证)
 *   token (Gotify token 认证)
 *   protocol=gotify (启用 Gotify WebSocket 协议，自动添加 /stream 路径)
 */
class WebSocketLink(override val config: LinkConfig) : Link {

    private var isGotifyProtocol = false

    override val id: String = config.id

    private var webSocket: WebSocket? = null
    private var connected = false
    private var messageListener: ((ByteArray) -> Unit)? = null
    private var errorListener: ((Exception) -> Unit)? = null
    override var reconnectCallback: (() -> Boolean)? = null
    private var lastHandshake: Handshake? = null
    @Volatile private var resolvedIp: String? = null
    private val connectionLock = Any()
    @Volatile private var connecting = false

    private var params: Map<String, String> = emptyMap()
    private var autoReconnect = true
    private var reconnectDelay = 5L
    private var maxReconnectDelay = 30L
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    init {
        // Validate DSN protocol is ws or wss
        val type = LinkType.fromDsn(config.dsn)
        if (type != LinkType.websocket) {
            throw IllegalArgumentException("WebSocketLink requires ws:// or wss:// DSN")
        }
    }

    override fun connect(): Boolean {
        if (connected) return true
        if (connecting) return true

        synchronized(connectionLock) {
            // Double-check after acquiring lock
            if (connected || connecting || webSocket != null) return true
            connecting = true

            val urlStr = config.dsn ?: return false

            // Parse URL params
            val (cleanUrl, parsedParams) = parseUrlParams(urlStr)
            params = parsedParams

            // Check for Gotify protocol
            isGotifyProtocol = params["protocol"] == "gotify"

            // Reconnect settings
            autoReconnect = params["automaticReconnect"]?.toBoolean()
                ?: (config.reconnect?.enabled ?: true)
            reconnectDelay = params["reconnectInterval"]?.toLongOrNull()
                ?: config.reconnect?.interval?.timeUnit?.toSeconds(config.reconnect?.interval?.millis ?: 3000) ?: 5L
            maxReconnectDelay = params["reconnectMaxInterval"]?.toLongOrNull()
                ?: config.reconnect?.maxInterval?.timeUnit?.toSeconds(config.reconnect?.maxInterval?.millis ?: 30000) ?: 30L

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
                    connected = true
                    connecting = false
                    reconnectJob?.cancel()
                    lastHandshake = response.handshake
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
                    connected = false
                    connecting = false
                    LogManager.log("WS", "Closed: $code $reason")
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connected = false
                    connecting = false
                    LogManager.logError("WS", "Error: ${t.message}")
                    errorListener?.invoke(t as? Exception ?: Exception(t.message))
                    scheduleReconnect()
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
            LogManager.log("WS", "Attempting reconnect: $id")
            reconnectCallback?.invoke() ?: connect()
        }
    }

    override fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        webSocket?.close(1000, "Disconnect requested")
        webSocket = null
        connected = false
        connecting = false
        LogManager.log("WS", "Disconnected: $id")
    }

    override fun isConnected(): Boolean = connected

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
                // Use cached resolved IP, or resolve now
                val ip = resolvedIp ?: try {
                    InetAddress.getByName(h).hostAddress?.also { resolvedIp = it }
                } catch (_: Exception) { null }
                if (ip != null && ip != h) details["resolved_ip"] = ip
            }
        } catch (_: Exception) {}
        return details
    }

    override fun getTlsInfo(): TlsConnectionInfo? {
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
