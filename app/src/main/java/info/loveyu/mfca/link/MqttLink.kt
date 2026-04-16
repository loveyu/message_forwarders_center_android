package info.loveyu.mfca.link

import android.content.Context
import android.os.Build
import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.CertResolver
import info.loveyu.mfca.util.LogManager
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * MQTT 连接实现
 */
class MqttLink(override val config: LinkConfig, private val context: Context) : Link {

    override val id: String = config.id

    private var client: MqttAsyncClient? = null
    @Volatile private var connected = false
    @Volatile private var connecting = false
    private var messageListener: ((String, MqttMessage) -> Unit)? = null
    private var errorListener: ((Exception) -> Unit)? = null

    private val topics = mutableMapOf<String, Int>() // topic -> qos

    private var consecutiveFailures = 0
    private var hadMaxFailure = false
    private var lastConnectAttempt = 0L
    private var retryIntervalMs = DEFAULT_RETRY_INTERVAL_MS
    private var autoReconnect = true
    private var maxReconnectDelay = 60L  // seconds
    override var maxFailureCallback: (() -> Unit)? = null
    override var recoveredCallback: (() -> Unit)? = null
    @Volatile private var peerCertificates: List<X509Certificate>? = null
    @Volatile private var resolvedIp: String? = null

    // Heartbeat monitoring
    private var keepAliveSeconds = 60
    private var connectedAt = 0L
    @Volatile private var lastOutboundActivity = 0L

    init {
        // Validate DSN protocol is mqtt or mqtts
        val type = LinkType.fromDsn(config.dsn)
        if (type != LinkType.mqtt) {
            throw IllegalArgumentException("MqttLink requires mqtt:// or mqtts:// DSN")
        }
    }

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val DEFAULT_RETRY_INTERVAL_MS = 10_000L // 10 seconds
    }

    @Synchronized
    override fun connect(): Boolean {
        if (connected) return true
        if (connecting) return false

        // Backoff: too many consecutive failures
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            LogManager.logWarn("MQTT", "Skipping connect for $id: too many failures ($consecutiveFailures), waiting for next health check")
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
        connecting = true

        val broker = config.dsn ?: return false

        // Parse DSN first to extract params
        val (rawBroker, params) = parseBrokerUrl(broker)

        // Store keepAlive for heartbeat monitoring
        keepAliveSeconds = params["keepAliveInterval"]?.toIntOrNull() ?: 60

        // Resolve retry interval: DSN param > config.reconnect > default
        retryIntervalMs = params["reconnectInterval"]?.toLongOrNull()?.let { it * 1000 }
            ?: (config.reconnect?.interval?.millis ?: DEFAULT_RETRY_INTERVAL_MS)

        // Resolve auto-reconnect: DSN param > config.reconnect.enabled > default true
        autoReconnect = params["automaticReconnect"]?.toBoolean()
            ?: (config.reconnect?.enabled ?: true)

        // Resolve max reconnect delay: DSN param > config.reconnect.maxInterval > default 60s
        maxReconnectDelay = params["reconnectMaxInterval"]?.toLongOrNull()
            ?: (config.reconnect?.maxInterval?.millis?.let { it / 1000 } ?: 60L)

        // Resolve clientId: query param > config.clientId > device ID
        val resolvedClientId = params["clientId"]
            ?: config.clientId
            ?: getDeviceId()

        try {
            // Paho MQTT requires tcp:// (non-TLS) or ssl:// (TLS), not mqtt:// / mqtts://
            val parsedBroker = when {
                rawBroker.startsWith("mqtts://") -> rawBroker.replaceFirst("mqtts://", "ssl://")
                rawBroker.startsWith("mqtt://") -> rawBroker.replaceFirst("mqtt://", "tcp://")
                else -> rawBroker
            }

            LogManager.log("MQTT", "Connecting to $parsedBroker as $resolvedClientId")

            val persistence = MemoryPersistence()
            client = MqttAsyncClient(parsedBroker, resolvedClientId, persistence)

            val options = MqttConnectOptions().apply {
                isCleanSession = params["cleanSession"]?.toBoolean() ?: true

                // Disable Paho's auto-reconnect; LinkManager handles reconnection
                isAutomaticReconnect = false

                connectionTimeout = params["connectTimeout"]?.toIntOrNull() ?: 10
                keepAliveInterval = params["keepAliveInterval"]?.toIntOrNull() ?: 60

                // User credentials: priority to URL params, then URL userinfo
                val user = params["username"]
                val pass = params["password"]
                if (user != null) {
                    userName = user
                }
                if (pass != null) {
                    password = pass.toCharArray()
                }

                // TLS: mqtts:// scheme or explicit tls config
                val isMqtts = rawBroker.startsWith("mqtts://")
                if (isMqtts || config.tls != null) {
                    val isInsecure = params["insecure"]?.toBoolean() ?: (config.tls?.insecure ?: false)
                    CertResolver.createSslConfig(config.tls, context, "MQTT", isInsecure) { certs ->
                        peerCertificates = certs
                    }?.let { socketFactory = it.socketFactory }
                }
            }

            client?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    val uptime = if (connectedAt > 0) (System.currentTimeMillis() - connectedAt) / 1000 else -1
                    connected = false
                    LinkManager.notifyLinkStateChanged(id, connected = false)
                    LogManager.logWarn("MQTT", "Connection lost after ${uptime}s (keepAlive=${keepAliveSeconds}s): ${cause?.javaClass?.simpleName}: ${cause?.message}")
                    if (cause != null) {
                        val trace = cause.stackTraceToString().lines().take(5).joinToString(" | ")
                        LogManager.logDebug("MQTT", "Connection lost trace: $trace")
                    }
                    cause?.let { errorListener?.invoke(it as? Exception ?: Exception(it.message)) }
                    // 触发立即重连，避免等待下一个周期 tick（最多 30s）
                    ForwardService.triggerTick()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    topic?.let { t ->
                        message?.let { msg ->
                            messageListener?.invoke(t, msg)
                            LogManager.logDebug("MQTT", "Message received on $t: ${msg.payload.size} bytes")
                        }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    LogManager.logDebug("MQTT", "Delivery complete")
                }
            })

            val result = client?.connect(options)
            result?.waitForCompletion(10000)

            if (client?.isConnected == true) {
                connected = true
                LinkManager.notifyLinkStateChanged(id, connected = true)
                val shouldNotify = hadMaxFailure && consecutiveFailures > 0
                consecutiveFailures = 0
                hadMaxFailure = false
                // Cache resolved IP
                try {
                    val uri = URI(parsedBroker)
                    uri.host?.let { h ->
                        resolvedIp = InetAddress.getByName(h).hostAddress
                    }
                } catch (_: Exception) {}
                LogManager.log("MQTT", "Connected successfully (keepAlive=${keepAliveSeconds}s)")
                connectedAt = System.currentTimeMillis()
                lastOutboundActivity = connectedAt

                if (shouldNotify) {
                    recoveredCallback?.invoke()
                }

                // Re-subscribe to topics
                if (topics.isNotEmpty()) {
                    LogManager.logDebug("MQTT", "Re-subscribing to ${topics.size} topics")
                    topics.forEach { (topic, qos) ->
                        client?.subscribe(topic, qos)
                    }
                }
                return true
            } else {
                consecutiveFailures++
                if (consecutiveFailures == MAX_CONSECUTIVE_FAILURES) hadMaxFailure = true
                LogManager.logWarn("MQTT", "Connection failed ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES): ${result?.exception?.message}")
                if (consecutiveFailures == MAX_CONSECUTIVE_FAILURES) {
                    maxFailureCallback?.invoke()
                }
                cleanupClient()
                return false
            }
        } catch (e: Exception) {
            consecutiveFailures++
            if (consecutiveFailures == MAX_CONSECUTIVE_FAILURES) hadMaxFailure = true
            LogManager.logError("MQTT", "Connection error ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES): ${e.message}")
            if (consecutiveFailures == MAX_CONSECUTIVE_FAILURES) {
                maxFailureCallback?.invoke()
            }
            errorListener?.invoke(e)
            cleanupClient()
            return false
        } finally {
            connecting = false
        }
    }

    @Synchronized
    override fun disconnect() {
        connecting = false
        consecutiveFailures = 0
        cleanupClient()
        LinkManager.notifyLinkStateChanged(id, connected = false)
        LogManager.log("MQTT", "Disconnected: $id")
    }

    private fun cleanupClient() {
        try {
            client?.disconnect(0)
        } catch (e: Exception) {
            LogManager.logDebug("MQTT", "Error during disconnect cleanup: ${e.message}")
        }
        try {
            client?.close()
        } catch (e: Exception) {
            LogManager.logDebug("MQTT", "Error closing client: ${e.message}")
        }
        client = null
        connected = false
    }

    /**
     * 采集心跳状态数据（非阻塞，仅读取 volatile 字段）。
     * 由 LinkManager 在 tick 中调用，将结果缓冲到下次 tick 再写入日志。
     */
    fun collectHeartbeatStatus(): String? {
        if (!connected) return null
        val now = System.currentTimeMillis()
        val uptimeSec = (now - connectedAt) / 1000
        val idleSec = (now - lastOutboundActivity) / 1000
        return "Heartbeat[$id]: uptime=${uptimeSec}s, idle=${idleSec}s, keepAlive=${keepAliveSeconds}s, pingExpected=${idleSec >= keepAliveSeconds}"
    }

    override fun isConnected(): Boolean = connected
    override fun isConnecting(): Boolean = connecting

    /**
     * Reset failure count so the next health check cycle can retry.
     */
    override fun resetFailureCount() {
        consecutiveFailures = 0
    }

    override fun shouldAutoReconnect(): Boolean = autoReconnect

    override fun send(data: ByteArray): Boolean {
        if (!connected) {
            LogManager.logWarn("MQTT", "Cannot send: not connected")
            return false
        }
        return false // Need topic to send - use sendToTopic
    }

    fun sendToTopic(topic: String, data: ByteArray, qos: Int = 1): Boolean {
        if (!connected || client == null) {
            LogManager.logWarn("MQTT", "Cannot send to topic: not connected")
            return false
        }

        return try {
            val message = MqttMessage(data)
            message.qos = qos
            lastOutboundActivity = System.currentTimeMillis()
            val token = client?.publish(topic, message)
            // Fire-and-forget: 不再 waitForCompletion，避免阻塞 RuleEngine worker 线程
            // 发布失败通过 connectionLost 回调感知
            LogManager.logDebug("MQTT", "Published to $topic: ${data.size} bytes")
            token != null
        } catch (e: Exception) {
            LogManager.logError("MQTT", "Publish error: ${e.message}")
            errorListener?.invoke(e)
            false
        }
    }

    fun subscribe(topic: String, qos: Int = 1): Boolean {
        if (!connected || client == null) {
            topics[topic] = qos
            LogManager.logDebug("MQTT", "Deferred subscribe to $topic (not connected)")
            return false
        }

        return try {
            client?.subscribe(topic, qos)
            topics[topic] = qos
            lastOutboundActivity = System.currentTimeMillis()
            LogManager.logDebug("MQTT", "Subscribed to $topic (QoS $qos)")
            true
        } catch (e: Exception) {
            LogManager.logError("MQTT", "Subscribe error: ${e.message}")
            errorListener?.invoke(e)
            false
        }
    }

    override fun send(text: String): Boolean = send(text.toByteArray())

    override fun setOnMessageListener(listener: (ByteArray) -> Unit) {
        messageListener = { _, msg -> listener(msg.payload) }
    }

    fun setOnMqttMessageListener(listener: (String, MqttMessage) -> Unit) {
        messageListener = listener
    }

    override fun setOnErrorListener(listener: (Exception) -> Unit) {
        errorListener = listener
    }

    override fun getConnectionDetails(): Map<String, String> {
        val details = mutableMapOf<String, String>()
        val broker = config.dsn ?: return details
        details["protocol"] = if (broker.startsWith("mqtts://")) "MQTTS" else "MQTT"
        try {
            val (cleanBroker, _) = parseBrokerUrl(broker)
            val transformed = when {
                cleanBroker.startsWith("mqtts://") -> cleanBroker.replaceFirst("mqtts://", "ssl://")
                cleanBroker.startsWith("mqtt://") -> cleanBroker.replaceFirst("mqtt://", "tcp://")
                else -> cleanBroker
            }
            val uri = URI(transformed)
            uri.host?.let { h ->
                details["host"] = h
                if (uri.port != -1) details["port"] = uri.port.toString()
                resolvedIp?.let { if (it != h) details["resolved_ip"] = it }
            }
        } catch (_: Exception) {}
        return details
    }

    override fun getTlsInfo(): TlsConnectionInfo? {
        val certs = peerCertificates ?: return null
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

    /**
     * Parse broker URL and extract connection parameters from query string and userinfo.
     * Example: mqtt://admin:123456@10.4.125.53:1883?connectTimeout=3&keepAliveInterval=60
     * Returns pair of (cleanBrokerUrl, paramsMap)
     */
    private fun parseBrokerUrl(broker: String): Pair<String, Map<String, String>> {
        val params = mutableMapOf<String, String>()

        // Extract query string parameters first
        val queryStart = broker.indexOf('?')
        val urlWithoutQuery = if (queryStart == -1) broker else broker.substring(0, queryStart)

        if (queryStart != -1) {
            val queryString = broker.substring(queryStart + 1)
            queryString.split('&').forEach { param ->
                val kv = param.split('=', limit = 2)
                if (kv.size == 2) {
                    val key = URLDecoder.decode(kv[0], "UTF-8")
                    val value = URLDecoder.decode(kv[1], "UTF-8")
                    params[key] = value
                }
            }
        }

        // Extract userinfo (user:pass@host) from URL, only if not already in query params
        // Format: scheme://user:pass@host:port
        val schemeEnd = urlWithoutQuery.indexOf("://")
        if (schemeEnd != -1) {
            val scheme = urlWithoutQuery.substring(0, schemeEnd + 3)
            val rest = urlWithoutQuery.substring(schemeEnd + 3)

            val atIndex = rest.lastIndexOf('@')
            if (atIndex != -1) {
                val userInfo = rest.substring(0, atIndex)
                val hostPort = rest.substring(atIndex + 1)

                val colonIndex = userInfo.indexOf(':')
                if (colonIndex != -1) {
                    // Only set from URL if not already provided via query params
                    if (!params.containsKey("username")) {
                        params["username"] = URLDecoder.decode(userInfo.substring(0, colonIndex), "UTF-8")
                    }
                    if (!params.containsKey("password")) {
                        params["password"] = URLDecoder.decode(userInfo.substring(colonIndex + 1), "UTF-8")
                    }
                }

                return Pair(scheme + hostPort, params)
            }
        }

        return Pair(urlWithoutQuery, params)
    }

    /**
     * 获取设备唯一标识
     * 使用 Build 属性组合生成
     */
    private fun getDeviceId(): String {
        return "mfca_${Build.BOARD}_${Build.BRAND}_${Build.DEVICE}_${Build.MODEL}_${Build.PRODUCT}".hashCode().toString(16)
    }
}
