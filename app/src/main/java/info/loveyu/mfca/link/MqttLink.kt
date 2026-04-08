package info.loveyu.mfca.link

import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.util.LogManager
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import java.io.File
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
            LogManager.appendLog("MQTT", "Connecting to $broker as $clientId")

            val persistence = MemoryPersistence()
            client = MqttAsyncClient(broker, clientId, persistence)

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = config.reconnect?.enabled ?: true
                connectionTimeout = 10
                keepAliveInterval = 60

                // TLS configuration
                if (config.tls != null) {
                    socketFactory = createSslSocketFactory(config.tls!!)
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

    private fun createSslSocketFactory(tls: info.loveyu.mfca.config.TlsConfig): javax.net.SocketFactory {
        try {
            val certFactory = CertificateFactory.getInstance("X.509")

            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())

            val keyStore = CertKeyStore.getInstance(CertKeyStore.getDefaultType())
            keyStore.load(null, null)

            // Load CA certificate if provided
            tls.ca?.let { caPath ->
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
