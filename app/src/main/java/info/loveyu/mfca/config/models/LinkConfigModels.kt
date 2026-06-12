package info.loveyu.mfca.config.models

data class LinkConfig(
    val id: String,
    val dsn: String? = null,
    val clientId: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val reconnect: ReconnectConfig? = null,
    val tls: TlsConfig? = null,
    val whenCondition: String? = null,
    val deny: String? = null
)

enum class LinkType {
    mqtt, websocket, tcp, http;

    companion object {
        fun fromDsn(dsn: String?): LinkType {
            if (dsn == null) return mqtt
            return when {
                dsn.startsWith("mqtt://") || dsn.startsWith("mqtts://") -> mqtt
                dsn.startsWith("ws://") || dsn.startsWith("wss://") -> websocket
                dsn.startsWith("tcp://") || dsn.startsWith("ssl://") -> tcp
                dsn.startsWith("http://") || dsn.startsWith("https://") -> http
                else -> mqtt
            }
        }
    }
}

data class ReconnectConfig(
    val enabled: Boolean = true,
    val interval: Duration = Duration("10s"),
    val maxInterval: Duration = Duration("60s")
)

data class TlsConfig(
    val ca: String? = null,
    val cert: String? = null,
    val key: String? = null,
    val insecure: Boolean = false
)
