package info.loveyu.mfca.config

import java.util.concurrent.TimeUnit

/**
 * 应用完整配置
 */
data class AppConfig(
    val version: String = "0.1",
    val links: List<LinkConfig> = emptyList(),
    val inputs: InputsConfig = InputsConfig(),
    val queues: QueuesConfig = QueuesConfig(),
    val outputs: OutputsConfig = OutputsConfig(),
    val rules: List<RuleConfig> = emptyList(),
    val deadLetter: DeadLetterConfig = DeadLetterConfig()
)

/**
 * 链接配置 (连接池)
 * type 通过 dsn 协议自动判断: mqtt:// → mqtt, ws:// → websocket, tcp:// → tcp
 */
data class LinkConfig(
    val id: String,
    val dsn: String? = null,  // 连接字符串，格式: protocol://user:pass@host:port?param=value
    val clientId: String? = null,
    val url: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val reconnect: ReconnectConfig? = null,
    val tls: TlsConfig? = null,
    val whenCondition: String? = null,  // 启用条件，URI query格式: network=wifi,ssid=MyWiFi
    val deny: String? = null  // 禁用条件，URI query格式: network=mobile
)

/**
 * 从 DSN 或 URL 协议推断链接类型
 */
enum class LinkType {
    mqtt, websocket, tcp;

    companion object {
        fun fromDsn(dsn: String?, url: String? = null): LinkType {
            // Check URL first for websocket (ws:// or wss://)
            if (url != null && (url.startsWith("ws://") || url.startsWith("wss://"))) {
                return websocket
            }
            if (dsn == null) return mqtt
            return when {
                dsn.startsWith("mqtt://") || dsn.startsWith("mqtts://") -> mqtt
                dsn.startsWith("ws://") || dsn.startsWith("wss://") -> websocket
                dsn.startsWith("tcp://") || dsn.startsWith("ssl://") -> tcp
                else -> mqtt
            }
        }
    }
}

data class ReconnectConfig(
    val enabled: Boolean = true,
    val interval: Duration = Duration("5s"),
    val maxInterval: Duration = Duration("60s")
)

data class TlsConfig(
    val ca: String? = null,
    val cert: String? = null,
    val key: String? = null
)

/**
 * 输入模块配置
 */
data class InputsConfig(
    val http: List<HttpInputConfig> = emptyList(),
    val link: List<LinkInputConfig> = emptyList()
)

data class HttpInputConfig(
    val name: String,
    val listen: String,
    val port: Int,
    val path: String,
    val auth: HttpAuthConfig? = null,
    val whenCondition: String? = null,
    val deny: String? = null
)

data class HttpAuthConfig(
    val type: HttpAuthType,
    val basic: BasicAuth? = null,
    val bearer: BearerAuth? = null,
    val query: QueryAuth? = null
)

enum class HttpAuthType {
    basic, bearer, query
}

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

data class LinkInputConfig(
    val name: String,
    val linkId: String,
    val role: LinkRole,
    val topic: String? = null,
    val topics: List<String>? = null,
    val excludeTopics: List<String>? = null,
    val qos: Int? = null,
    val whenCondition: String? = null,
    val deny: String? = null
)

enum class LinkRole {
    consumer, producer
}

/**
 * 队列系统配置
 */
data class QueuesConfig(
    val memory: Map<String, MemoryQueueConfig> = emptyMap(),
    val sqlite: Map<String, SqliteQueueConfig> = emptyMap()
)

data class MemoryQueueConfig(
    val capacity: Int = 1000,
    val workers: Int = 2,
    val overflow: OverflowStrategy = OverflowStrategy.dropOldest
)

enum class OverflowStrategy {
    dropOldest, dropNew, block
}

data class SqliteQueueConfig(
    val path: String,
    val batchSize: Int = 20,
    val retryInterval: Duration = Duration("5s"),
    val maxRetry: Int = 10,
    val backoff: BackoffConfig? = null,
    val cleanup: CleanupConfig? = null
)

data class BackoffConfig(
    val type: BackoffType = BackoffType.exponential,
    val initial: Duration = Duration("2s"),
    val max: Duration = Duration("5m")
)

enum class BackoffType {
    exponential, linear
}

data class CleanupConfig(
    val maxAge: Duration = Duration("7d")
)

/**
 * 输出模块配置
 */
data class OutputsConfig(
    val http: List<HttpOutputConfig> = emptyList(),
    val link: List<LinkOutputConfig> = emptyList(),
    val internal: List<InternalOutputConfig> = emptyList()
)

data class HttpOutputConfig(
    val name: String,
    val url: String,
    val method: String = "POST",
    val timeout: Duration = Duration("5s"),
    val retry: RetryConfig? = null,
    val queue: QueueRefConfig? = null
)

data class RetryConfig(
    val maxAttempts: Int = 1,
    val interval: Duration = Duration("1s")
)

data class QueueRefConfig(
    val priority: String? = null,
    val memoryQueue: String? = null,
    val sqliteQueue: String? = null
)

data class LinkOutputConfig(
    val name: String,
    val linkId: String,
    val role: LinkRole,
    val topic: String? = null,
    val queue: QueueRefConfig? = null,
    val whenCondition: String? = null,
    val deny: String? = null
)

data class InternalOutputConfig(
    val name: String,
    val type: InternalOutputType,
    val basePath: String? = null,
    val options: Map<String, Any>? = null,
    val channel: String? = null
)

enum class InternalOutputType {
    clipboard, file, broadcast
}

/**
 * 转发规则配置
 */
data class RuleConfig(
    val name: String,
    val from: String,
    val pipeline: List<PipelineStep> = emptyList(),
    val onError: List<PipelineStep>? = null,
    val whenCondition: String? = null,
    val deny: String? = null
)

data class PipelineStep(
    val transform: TransformConfig? = null,
    val to: List<String> = emptyList()
)

data class TransformConfig(
    val extract: String? = null,
    val filter: String? = null,
    val detect: String? = null
)

/**
 * 死信队列配置
 */
data class DeadLetterConfig(
    val enabled: Boolean = false,
    val maxRetry: Int = 10,
    val action: List<PipelineStep> = emptyList()
)

/**
 * 通用配置
 */
data class Duration(
    val value: String
) {
    val millis: Long
        get() = parseDuration(value)

    val timeUnit: TimeUnit
        get() = when {
            value.endsWith("ms") -> TimeUnit.MILLISECONDS
            value.endsWith("s") -> TimeUnit.SECONDS
            value.endsWith("m") -> TimeUnit.MINUTES
            value.endsWith("h") -> TimeUnit.HOURS
            value.endsWith("d") -> TimeUnit.DAYS
            else -> TimeUnit.SECONDS
        }

    private fun parseDuration(d: String): Long {
        return try {
            val num = d.dropLast(1).toLongOrNull() ?: 0
            val unit = d.takeLast(1)
            when (unit) {
                "ms" -> num
                "s" -> num * 1000
                "m" -> num * 60 * 1000
                "h" -> num * 60 * 60 * 1000
                "d" -> num * 24 * 60 * 60 * 1000
                else -> num * 1000
            }
        } catch (e: Exception) {
            5000
        }
    }
}
