package info.loveyu.mfca.config

import java.util.concurrent.TimeUnit

/**
 * 快捷设置配置（通知栏按钮开关）
 */
data class QuickSettingsConfig(
    val inputMethodSwitcher: Boolean = true
)

/**
 * 应用完整配置
 */
data class AppConfig(
    val version: String = "",
    val scheduler: SchedulerConfig = SchedulerConfig(),
    val links: List<LinkConfig> = emptyList(),
    val inputs: InputsConfig = InputsConfig(),
    val queues: QueuesConfig = QueuesConfig(),
    val outputs: OutputsConfig = OutputsConfig(),
    val rules: List<RuleConfig> = emptyList(),
    val deadLetter: DeadLetterConfig = DeadLetterConfig(),
    val quickSettings: QuickSettingsConfig = QuickSettingsConfig()
)

/**
 * 统一调度器配置
 */
data class SchedulerConfig(
    val tickInterval: Duration = Duration("30s")
) {
    /** 保证最小 15 秒 */
    val effectiveTickInterval: Duration
        get() = if (tickInterval.millis >= 15_000) tickInterval else Duration("15s")
}

/**
 * 链接配置 (连接池)
 * type 通过 dsn 协议自动判断: mqtt:// → mqtt, ws:// → websocket, tcp:// → tcp
 */
data class LinkConfig(
    val id: String,
    val dsn: String? = null,  // 连接字符串，格式: protocol://user:pass@host:port?param=value
    val clientId: String? = null,
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

/**
 * 输入模块配置
 */
data class InputsConfig(
    val http: List<HttpInputConfig> = emptyList(),
    val link: List<LinkInputConfig> = emptyList()
)

data class HttpInputConfig(
    val name: String,
    val dsn: String,
    val paths: List<String> = listOf("/"),
    val linkId: String? = null,
    val whenCondition: String? = null,
    val deny: String? = null
)

/**
 * DSN 解析后的 HTTP 输入配置
 */
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
    val linkId: String,
    val linkIds: List<String> = emptyList(),
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
    val fileName: String? = null,
    val options: Map<String, Any>? = null,
    val channel: String? = null
)

enum class InternalOutputType {
    clipboard, file, broadcast, notify
}

/**
 * 通知选项
 *
 * 模板变量说明 (tag、group、id 支持):
 * - {channel} - 输出配置的 channel
 * - {name} - 输出配置的 name
 * - {seq} - 全局递增序列号 (0-999 循环)
 * - {timestamp} - 秒级时间戳 (10位)
 * - {unix} - 毫秒级时间戳 (13位)
 * - {date:format} - 格式化日期，如 {date:yyyyMMddHHmmss}
 * - {data} - 原始数据内容 (UTF-8 解码)
 * - {data.path} - JSON 路径提取 (当 data 为 JSON 时)
 * - {meta.key} - metadata 字段
 *
 * 标识字段语义:
 * - tag:   通知替换域，相同 tag+id 的通知替换旧通知。默认为 name (输出名称)
 * - group: 通知栏视觉分组，相同 group 的通知折叠在一起。默认与 tag 相同
 * - id:    通知唯一标识，Int 值。默认为秒级时间戳 * 1000 + 序列号
 *
 * 运行时覆盖:
 * - metadata:  notify_tag / notify_group / notify_id / notify_popup / notify_persistent (带 notify_ 前缀)
 * - data JSON: 内容字段直接读取 (title/message/icon/fixedIcon)，控制字段使用 notify 前缀 (notifyTag/notifyGroup/notifyId/notifyPopup/notifyPersistent)
 */
data class NotifyOptions(
    var title: String? = null,
    var message: String? = null,
    var icon: String? = null,
    var fixedIcon: String? = null,
    var popup: Boolean? = null,
    var persistent: Boolean? = null,
    var tag: String? = null,
    var group: String? = null,
    var id: String? = null
)

/**
 * 转发规则配置
 */
data class RuleConfig(
    val name: String,
    val from: String,
    val froms: List<String> = emptyList(),
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
    val detect: String? = null,
    val format: String? = null,
    val enrich: String? = null  // "enricherType:parameter", e.g., "gotifyIcon:gotify_link"
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
            // "ms" suffix must be checked first to avoid mis-detecting as minutes
            val isMillis = d.endsWith("ms")
            val numStr = if (isMillis) d.dropLast(2) else d.dropLast(1)
            val num = numStr.toLongOrNull() ?: 0
            val unit = if (isMillis) "ms" else d.takeLast(1)
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
