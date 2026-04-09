package info.loveyu.mfca.link

import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
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
class MqttLink(override val config: LinkConfig) : Link {

    override val id: String = config.id

    private var client: MqttAsyncClient? = null
    private var connected = false
    private var messageListener: ((ByteArray) -> Unit)? = null
    private var errorListener: ((Exception) -> Unit)? = null

    private val topics = mutableMapOf<String, Int>() // topic -> qos

    init {
        if (config.type != LinkType.mqtt) {
            throw IllegalArgumentException("MqttLink requires mqtt type config")
        }
    }

    override fun connect(): Boolean {
        if (connected) return true

        val broker = config.broker ?: return false
        val clientId = config.clientId ?: "mfca_${System.currentTimeMillis()}"

        try {
            // Parse broker URL to extract connection parameters from query string
            val (rawBroker, params) = parseBrokerUrl(broker)
            // Paho MQTT requires tcp:// (non-TLS) or ssl:// (TLS), not mqtt:// / mqtts://
            val parsedBroker = when {
                rawBroker.startsWith("mqtts://") -> rawBroker.replaceFirst("mqtts://", "ssl://")
                rawBroker.startsWith("mqtt://") -> rawBroker.replaceFirst("mqtt://", "tcp://")
                else -> rawBroker
            }

            LogManager.appendLog("MQTT", "Connecting to $parsedBroker as $clientId")

            val persistence = MemoryPersistence()
            client = MqttAsyncClient(parsedBroker, clientId, persistence)

            val options = MqttConnectOptions().apply {
                isCleanSession = params["cleanSession"]?.toBoolean() ?: true

                // Reconnect: priority to URL params, then config, then defaults
                val autoReconnectEnabled = params["automaticReconnect"]?.toBoolean()
                    ?: config.reconnect?.enabled ?: true
                isAutomaticReconnect = autoReconnectEnabled

                if (autoReconnectEnabled) {
                    // Paho MQTT's setAutomaticReconnect only takes boolean, reconnect timing is internal
                    setAutomaticReconnect(true)
                }

                connectionTimeout = params["connectTimeout"]?.toIntOrNull() ?: 10
                keepAliveInterval = params["keepAliveInterval"]?.toIntOrNull() ?: 60

                // User credentials from query parameters
                params["username"]?.let { userName = it }
                params["password"]?.let { password = it.toCharArray() }

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
                            messageListener?.invoke(msg.payload)
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
                LogManager.appendLog("MQTT", "Connected successfully")

                // Re-subscribe to topics
                topics.forEach { (topic, qos) ->
                    client?.subscribe(topic, qos)
                }
                return true
            } else {
                LogManager.appendLog("MQTT", "Connection failed: ${result?.exception?.message}")
                return false
            }
        } catch (e: Exception) {
            LogManager.appendLog("MQTT", "Connection error: ${e.message}")
            errorListener?.invoke(e)
            return false
        }
    }

    override fun disconnect() {
        try {
            client?.disconnect()
            client?.close()
            connected = false
            LogManager.appendLog("MQTT", "Disconnected: $id")
        } catch (e: Exception) {
            LogManager.appendLog("MQTT", "Disconnect error: ${e.message}")
        }
    }

    override fun isConnected(): Boolean = connected

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
        messageListener = listener
    }

    override fun setOnErrorListener(listener: (Exception) -> Unit) {
        errorListener = listener
    }

    /**
     * Parse broker URL and extract connection parameters from query string.
     * Example: mqtt://admin:123456@10.4.125.53:1883?connectTimeout=3&keepAliveInterval=60
     * Returns pair of (cleanBrokerUrl, paramsMap)
     */
    private fun parseBrokerUrl(broker: String): Pair<String, Map<String, String>> {
        val params = mutableMapOf<String, String>()

        val queryStart = broker.indexOf('?')
        if (queryStart == -1) {
            return Pair(broker, params)
        }

        // Extract query string and parse parameters
        val queryString = broker.substring(queryStart + 1)
        val cleanBroker = broker.substring(0, queryStart)

        queryString.split('&').forEach { param ->
            val kv = param.split('=', limit = 2)
            if (kv.size == 2) {
                val key = URLDecoder.decode(kv[0], "UTF-8")
                val value = URLDecoder.decode(kv[1], "UTF-8")
                params[key] = value
            }
        }

        return Pair(cleanBroker, params)
    }

    private fun createSslSocketFactory(tls: info.loveyu.mfca.config.TlsConfig?): javax.net.SocketFactory {
        try {
            val certFactory = CertificateFactory.getInstance("X.509")

            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())

            val keyStore = CertKeyStore.getInstance(CertKeyStore.getDefaultType())
            keyStore.load(null, null)

            // Load CA certificate if provided
            tls?.ca?.let { caPath ->
                val caFile = File(caPath)
                if (caFile.exists()) {
                    val caCert = certFactory.generateCertificate(caFile.inputStream()) as X509Certificate
                    keyStore.setCertificateEntry("ca", caCert)
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
}
