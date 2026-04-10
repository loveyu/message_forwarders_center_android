package info.loveyu.mfca.link

import android.content.Context
import android.os.Build
import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.util.CertResolver
import info.loveyu.mfca.util.LogManager
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.File
import java.net.URLDecoder
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore as CertKeyStore

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
    private var lastConnectAttempt = 0L

    init {
        // Validate DSN protocol is mqtt or mqtts
        val type = LinkType.fromDsn(config.dsn)
        if (type != LinkType.mqtt) {
            throw IllegalArgumentException("MqttLink requires mqtt:// or mqtts:// DSN")
        }
    }

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val MIN_RETRY_INTERVAL_MS = 10_000L // 10 seconds
    }

    @Synchronized
    override fun connect(): Boolean {
        if (connected) return true
        if (connecting) return false

        // Backoff: too many consecutive failures
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            LogManager.appendLog("MQTT", "Skipping connect for $id: too many failures ($consecutiveFailures), waiting for next health check")
            return false
        }

        // Backoff: don't retry too fast
        val now = System.currentTimeMillis()
        if (now - lastConnectAttempt < MIN_RETRY_INTERVAL_MS) {
            return false
        }
        lastConnectAttempt = now
        connecting = true

        val broker = config.dsn ?: return false

        // Parse DSN first to extract params
        val (rawBroker, params) = parseBrokerUrl(broker)

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

            LogManager.appendLog("MQTT", "Connecting to $parsedBroker as $resolvedClientId")

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
                    socketFactory = createSslSocketFactory(config.tls)
                }
            }

            client?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    connected = false
                    LogManager.appendLog("MQTT", "Connection lost: ${cause?.message}")
                    cause?.let { errorListener?.invoke(it as? Exception ?: Exception(it.message)) }
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    topic?.let { t ->
                        message?.let { msg ->
                            messageListener?.invoke(t, msg)
                            LogManager.appendLog("MQTT", "Message received on $t: ${String(msg.payload).take(100)}")
                        }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    LogManager.appendLog("MQTT", "Delivery complete")
                }
            })

            val result = client?.connect(options)
            result?.waitForCompletion(10000)

            if (client?.isConnected == true) {
                connected = true
                consecutiveFailures = 0
                LogManager.appendLog("MQTT", "Connected successfully")

                // Re-subscribe to topics
                topics.forEach { (topic, qos) ->
                    client?.subscribe(topic, qos)
                }
                return true
            } else {
                consecutiveFailures++
                LogManager.appendLog("MQTT", "Connection failed ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES): ${result?.exception?.message}")
                cleanupClient()
                return false
            }
        } catch (e: Exception) {
            consecutiveFailures++
            LogManager.appendLog("MQTT", "Connection error ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES): ${e.message}")
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
        LogManager.appendLog("MQTT", "Disconnected: $id")
    }

    private fun cleanupClient() {
        try {
            client?.disconnect(0)
        } catch (_: Exception) {
        }
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
        connected = false
    }

    override fun isConnected(): Boolean = connected

    /**
     * Reset failure count so the next health check cycle can retry.
     */
    fun resetFailureCount() {
        consecutiveFailures = 0
    }

    override fun send(data: ByteArray): Boolean {
        if (!connected) {
            LogManager.appendLog("MQTT", "Cannot send: not connected")
            return false
        }
        return false // Need topic to send - use sendToTopic
    }

    fun sendToTopic(topic: String, data: ByteArray, qos: Int = 1): Boolean {
        if (!connected || client == null) {
            LogManager.appendLog("MQTT", "Cannot send: not connected")
            return false
        }

        return try {
            val message = MqttMessage(data)
            message.qos = qos
            val token = client?.publish(topic, message)
            token?.waitForCompletion(5000)
            LogManager.appendLog("MQTT", "Published to $topic: ${data.size} bytes")
            true
        } catch (e: Exception) {
            LogManager.appendLog("MQTT", "Publish error: ${e.message}")
            errorListener?.invoke(e)
            false
        }
    }

    fun subscribe(topic: String, qos: Int = 1): Boolean {
        if (!connected || client == null) {
            topics[topic] = qos
            return false
        }

        return try {
            client?.subscribe(topic, qos)
            topics[topic] = qos
            LogManager.appendLog("MQTT", "Subscribed to $topic (QoS $qos)")
            true
        } catch (e: Exception) {
            LogManager.appendLog("MQTT", "Subscribe error: ${e.message}")
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

    private fun createSslSocketFactory(tls: info.loveyu.mfca.config.TlsConfig?): javax.net.SocketFactory {
        try {
            // 解析 TLS 证书路径（支持 file://, sdcard://, https://, http://）
            val resolvedTls = CertResolver.resolveTlsConfig(tls, context)

            val certFactory = CertificateFactory.getInstance("X.509")

            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())

            val keyStore = CertKeyStore.getInstance(CertKeyStore.getDefaultType())
            keyStore.load(null, null)

            // Load CA certificate if provided
            resolvedTls?.ca?.let { caPath ->
                val caFile = File(caPath)
                if (caFile.exists()) {
                    val caCert = certFactory.generateCertificate(caFile.inputStream()) as X509Certificate
                    keyStore.setCertificateEntry("ca", caCert)
                    LogManager.appendLog("MQTT", "Loaded CA cert from: $caPath")
                } else {
                    LogManager.appendLog("MQTT", "CA cert file not found: $caPath")
                }
            }

            trustManagerFactory.init(keyStore)

            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(null, trustManagerFactory.trustManagers, null)

            return sslContext.socketFactory
        } catch (e: Exception) {
            LogManager.appendLog("MQTT", "SSL setup error: ${e.message}")
            throw e
        }
    }

    /**
     * 获取设备唯一标识
     * 使用 Build 属性组合生成
     */
    private fun getDeviceId(): String {
        return "mfca_${Build.BOARD}_${Build.BRAND}_${Build.DEVICE}_${Build.MODEL}_${Build.PRODUCT}".hashCode().toString(16)
    }
}
