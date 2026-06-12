package info.loveyu.mfca.config.models

data class InputsConfig(
    val http: List<HttpInputConfig> = emptyList(),
    val link: List<LinkInputConfig> = emptyList(),
    val udp2raw: List<Udp2RawInputConfig> = emptyList(),
    val m2m: List<M2mInputConfig> = emptyList()
)

data class HttpInputConfig(
    val name: String,
    val dsn: String,
    val paths: List<String> = emptyList(),
    val linkId: String? = null,
    val whenCondition: String? = null,
    val deny: String? = null
)

data class HttpInputParsedConfig(
    val listen: String,
    val port: Int,
    val methods: List<String> = emptyList(),
    val basicAuth: BasicAuth? = null,
    val bearerAuth: BearerAuth? = null,
    val queryAuth: QueryAuth? = null,
    val cookieAuth: CookieAuth? = null,
    val allowIps: List<String> = emptyList(),
    val denyIps: List<String> = emptyList()
)

data class BasicAuth(
    val username: String,
    val password: String
)

data class BearerAuth(
    val token: String
)

data class QueryAuth(
    val key: String,
    val value: String
)

data class CookieAuth(
    val key: String,
    val value: String
)

data class LinkInputConfig(
    val name: String,
    val linkIds: List<String>,
    val role: LinkRole,
    val topic: String? = null,
    val topics: List<String>? = null,
    val excludeTopics: List<String>? = null,
    val qos: Int? = null,
    val replay: ReplayConfig? = null,
    val whenCondition: String? = null,
    val deny: String? = null
) {
    val linkId: String get() = linkIds.firstOrNull() ?: ""
}

enum class LinkRole {
    consumer, producer
}

data class M2mInputConfig(
    val name: String,
    val configUrl: String,
    val refreshIntervalMs: Long = 0L,
    val whenCondition: String? = null,
    val deny: String? = null,
    val enabled: Boolean = true,
    val insecure: Boolean = false,
    val accessControlMode: M2mAccessControlMode = M2mAccessControlMode.acceptAll,
    val packages: List<String> = emptyList(),
)

data class Udp2RawInputConfig(
    val name: String,
    val dsn: String? = null,
    val args: List<String> = emptyList(),
    val enabled: Boolean = true,
    val whenCondition: String? = null,
    val deny: String? = null,
)

enum class M2mAccessControlMode {
    acceptAll, include, exclude
}

data class ReplayConfig(
    val enabled: Boolean = false,
    val provider: ReplayProvider = ReplayProvider.gotifyApi,
    val messageIdPath: String = "id",
    val pageSize: Int = 50,
    val maxPages: Int = 20,
    val maxMessages: Int = 500,
    val persistState: Boolean = true,
    val baseUrl: String? = null,
    val token: String? = null,
    val applicationId: Int? = null
)

enum class ReplayProvider {
    gotifyApi
}
