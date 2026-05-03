package info.loveyu.mfca.config

import info.loveyu.mfca.util.LogManager
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

object ConfigLoader {

    private val yaml = Load(LoadSettings.builder().build())

    fun loadConfig(yamlString: String): AppConfig {
        return try {
            val data = yaml.loadFromString(yamlString) as? Map<String, Any>
                ?: throw IllegalArgumentException("Invalid YAML format")

            AppConfig(
                version = data["version"] as? String ?: "",
                scheduler = parseScheduler(data["scheduler"]),
                links = parseLinks(data["links"]),
                inputs = parseInputs(data["inputs"]),
                queues = parseQueues(data["queues"]),
                outputs = parseOutputs(data["outputs"]),
                rules = parseRules(data["rules"]),
                deadLetter = parseDeadLetter(data["deadLetter"]),
                quickSettings = parseQuickSettings(data["quickSettings"])
            )
        } catch (e: Exception) {
            throw ConfigLoadException("Failed to parse config: ${e.message}", e)
        }
    }

    fun loadFromFile(path: String): AppConfig {
        val file = File(path)
        if (!file.exists()) {
            throw ConfigLoadException("Config file not found: $path")
        }
        return loadConfig(file.readText())
    }

    // ==================== Scheduler Parsing ====================

    private fun parseScheduler(scheduler: Any?): SchedulerConfig {
        if (scheduler == null) return SchedulerConfig()
        val map = scheduler as Map<String, Any>
        return SchedulerConfig(
            tickInterval = Duration(map["tickInterval"] as? String ?: "40s"),
            chargingTickInterval = (map["chargingTickInterval"] as? String)?.let { Duration(it) },
            wakeLockTimeout = Duration(map["wakeLockTimeout"] as? String ?: "1h"),
            wifiLockTimeout = Duration(map["wifiLockTimeout"] as? String ?: "1h")
        )
    }

    // ==================== Link Parsing ====================

    private fun parseLinks(links: Any?): List<LinkConfig> {
        if (links == null) return emptyList()

        return (links as List<*>).mapNotNull { link ->
            (link as? Map<String, Any>)?.let { map ->
                LinkConfig(
                    id = map["id"] as? String ?: return@mapNotNull null,
                    dsn = map["dsn"] as? String,
                    clientId = (map["clientId"] as? String),
                    host = map["host"] as? String,
                    port = (map["port"] as? Number)?.toInt(),
                    reconnect = parseReconnect(map["reconnect"]),
                    tls = parseTls(map["tls"]),
                    whenCondition = map["when"] as? String,
                    deny = map["deny"] as? String
                )
            }
        }
    }

    private fun parseTls(tls: Any?): TlsConfig? {
        if (tls == null) return null
        val map = tls as Map<String, Any>
        return TlsConfig(
            ca = map["ca"] as? String,
            cert = map["cert"] as? String,
            key = map["key"] as? String,
            insecure = map["insecure"] as? Boolean ?: false
        )
    }

    private fun parseReconnect(reconnect: Any?): ReconnectConfig? {
        if (reconnect == null) return null
        val map = reconnect as Map<String, Any>
        return ReconnectConfig(
            enabled = map["enabled"] as? Boolean ?: true,
            interval = Duration(map["interval"] as? String ?: "10s"),
            maxInterval = Duration(map["maxInterval"] as? String ?: "60s")
        )
    }

    // ==================== Input Parsing ====================

    private fun parseInputs(inputs: Any?): InputsConfig {
        if (inputs == null) return InputsConfig()

        val map = inputs as Map<String, Any>
        return InputsConfig(
            http = parseHttpInputs(map["http"]),
            link = parseLinkInputs(map["link"]),
            failQueue = parseFailQueueInputs(map["failQueue"])
        )
    }

    private fun parseHttpInputs(http: Any?): List<HttpInputConfig> {
        if (http == null) return emptyList()

        return (http as List<*>).mapNotNull { input ->
            (input as? Map<String, Any>)?.let { map ->
                HttpInputConfig(
                    name = map["name"] as? String ?: return@mapNotNull null,
                    dsn = map["dsn"] as? String ?: return@mapNotNull null,
                    paths = (map["paths"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                    linkId = map["linkId"] as? String,
                    whenCondition = map["when"] as? String,
                    deny = map["deny"] as? String
                )
            }
        }
    }

    private fun parseLinkInputs(link: Any?): List<LinkInputConfig> {
        if (link == null) return emptyList()

        return (link as List<*>).mapNotNull { input ->
            (input as? Map<String, Any>)?.let { map ->
                val linkIds = parseStringOrList(map["linkId"])
                if (linkIds.isEmpty()) return@mapNotNull null
                LinkInputConfig(
                    name = map["name"] as? String ?: return@mapNotNull null,
                    linkId = linkIds.first(),
                    linkIds = linkIds,
                    role = parseLinkRole(map["role"] as? String),
                    topic = map["topic"] as? String,
                    topics = (map["topics"] as? List<*>)?.mapNotNull { it as? String },
                    excludeTopics = (map["excludeTopics"] as? List<*>)?.mapNotNull { it as? String },
                    qos = (map["qos"] as? Number)?.toInt(),
                    replay = parseReplay(map["replay"]),
                    whenCondition = map["when"] as? String,
                    deny = map["deny"] as? String
                )
            }
        }
    }

    private fun parseLinkRole(role: String?): LinkRole {
        return when (role?.lowercase()) {
            "consumer" -> LinkRole.consumer
            "producer" -> LinkRole.producer
            else -> LinkRole.consumer
        }
    }

    private fun parseFailQueueInputs(failQueue: Any?): List<FailQueueInputConfig> {
        if (failQueue == null) return emptyList()
        return (failQueue as List<*>).mapNotNull { item ->
            (item as? Map<String, Any>)?.let { map ->
                FailQueueInputConfig(
                    name = map["name"] as? String ?: return@mapNotNull null,
                    idTypes = (map["idTypes"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                    queues = parseStringOrList(map["queues"]),
                    batchSize = (map["batchSize"] as? Number)?.toInt() ?: 20
                )
            }
        }
    }

    private fun parseOnFailure(onFailure: Any?): OnFailureConfig? {
        if (onFailure == null) return null
        val map = onFailure as? Map<String, Any> ?: return null
        return OnFailureConfig(
            action = when (map["action"] as? String) {
                "failQueue" -> OnFailureAction.failQueue
                else -> OnFailureAction.discard
            },
            idType = map["idType"] as? String,
            queue = map["queue"] as? String,
            delay = Duration(map["delay"] as? String ?: "60s")
        )
    }

    private fun parseReplay(replay: Any?): ReplayConfig? {
        if (replay == null) return null
        val map = replay as? Map<String, Any> ?: return null
        return ReplayConfig(
            enabled = map["enabled"] as? Boolean ?: false,
            provider = parseReplayProvider(map["provider"] as? String),
            messageIdPath = map["messageIdPath"] as? String ?: "id",
            pageSize = (map["pageSize"] as? Number)?.toInt() ?: 50,
            maxPages = (map["maxPages"] as? Number)?.toInt() ?: 20,
            maxMessages = (map["maxMessages"] as? Number)?.toInt() ?: 500,
            persistState = map["persistState"] as? Boolean ?: true,
            baseUrl = map["baseUrl"] as? String,
            token = map["token"] as? String,
            applicationId = (map["applicationId"] as? Number)?.toInt()
        )
    }

    private fun parseReplayProvider(provider: String?): ReplayProvider {
        return when (provider?.lowercase()) {
            "gotifyapi", "gotify_api", "gotify-api" -> ReplayProvider.gotifyApi
            else -> ReplayProvider.gotifyApi
        }
    }

    /**
     * 解析字符串或字符串数组字段，统一返回 List<String>
     */
    private fun parseStringOrList(value: Any?): List<String> {
        return when (value) {
            is String -> listOf(value)
            is List<*> -> value.mapNotNull { it as? String }
            else -> emptyList()
        }
    }

    // ==================== Queue Parsing ====================

    private fun parseQueues(queues: Any?): QueuesConfig {
        if (queues == null) return QueuesConfig()

        val map = queues as Map<String, Any>
        return QueuesConfig(
            memory = parseMemoryQueues(map["memory"]),
            sqlite = parseSqliteQueues(map["sqlite"])
        )
    }

    private fun parseMemoryQueues(memory: Any?): Map<String, MemoryQueueConfig> {
        if (memory == null) return emptyMap()

        val result = mutableMapOf<String, MemoryQueueConfig>()
        (memory as Map<String, Any>).forEach { (name, config) ->
            (config as? Map<String, Any>)?.let { map ->
                result[name] = MemoryQueueConfig(
                    capacity = (map["capacity"] as? Number)?.toInt() ?: 1000,
                    workers = (map["workers"] as? Number)?.toInt() ?: 2,
                    overflow = parseOverflowStrategy(map["overflow"] as? String)
                )
            }
        }
        return result
    }

    private fun parseOverflowStrategy(strategy: String?): OverflowStrategy {
        return when (strategy?.lowercase()) {
            "drop_oldest" -> OverflowStrategy.dropOldest
            "drop_new" -> OverflowStrategy.dropNew
            "block" -> OverflowStrategy.block
            else -> OverflowStrategy.dropOldest
        }
    }

    private fun parseSqliteQueues(sqlite: Any?): Map<String, SqliteQueueConfig> {
        if (sqlite == null) return emptyMap()

        val result = mutableMapOf<String, SqliteQueueConfig>()
        (sqlite as Map<String, Any>).forEach { (name, config) ->
            (config as? Map<String, Any>)?.let { map ->
                result[name] = SqliteQueueConfig(
                    path = map["path"] as? String ?: "",
                    batchSize = (map["batchSize"] as? Number)?.toInt() ?: 20,
                    retryInterval = Duration(map["retryInterval"] as? String ?: "5s"),
                    maxRetry = (map["maxRetry"] as? Number)?.toInt() ?: 10,
                    backoff = parseBackoff(map["backoff"]),
                    cleanup = parseCleanup(map["cleanup"])
                )
            }
        }
        return result
    }

    private fun parseBackoff(backoff: Any?): BackoffConfig? {
        if (backoff == null) return null
        val map = backoff as Map<String, Any>
        return BackoffConfig(
            type = when ((map["type"] as? String)?.lowercase()) {
                "exponential" -> BackoffType.exponential
                "linear" -> BackoffType.linear
                else -> BackoffType.exponential
            },
            initial = Duration(map["initial"] as? String ?: "2s"),
            max = Duration(map["max"] as? String ?: "5m")
        )
    }

    private fun parseCleanup(cleanup: Any?): CleanupConfig? {
        if (cleanup == null) return null
        val map = cleanup as Map<String, Any>
        return CleanupConfig(
            maxAge = Duration(map["maxAge"] as? String ?: "7d")
        )
    }

    // ==================== Output Parsing ====================

    private fun parseOutputs(outputs: Any?): OutputsConfig {
        if (outputs == null) return OutputsConfig()

        val map = outputs as Map<String, Any>
        return OutputsConfig(
            http = parseHttpOutputs(map["http"]),
            link = parseLinkOutputs(map["link"]),
            internal = parseInternalOutputs(map["internal"])
        )
    }

    private fun parseFormatSteps(format: Any?): List<OutputFormatStep>? {
        if (format == null) return null
        return when (format) {
            is String -> listOf(OutputFormatStep(target = "\$data", template = format))
            is List<*> ->
                format.mapNotNull { item ->
                    (item as? Map<*, *>)?.entries?.firstOrNull()?.let { (k, v) ->
                        OutputFormatStep(target = k.toString(), template = v.toString())
                    }
                }
            else -> null
        }
    }

    private fun parseHttpOutputs(http: Any?): List<HttpOutputConfig> {
        if (http == null) return emptyList()

        return (http as List<*>).mapNotNull { output ->
            (output as? Map<String, Any>)?.let { map ->
                HttpOutputConfig(
                    name = map["name"] as? String ?: return@mapNotNull null,
                    url = map["url"] as? String ?: "",
                    method = map["method"] as? String ?: "POST",
                    timeout = Duration(map["timeout"] as? String ?: "5s"),
                    retry = parseRetry(map["retry"]),
                    queue = parseQueueRef(map["queue"]),
                    format = parseFormatSteps(map["format"])
                )
            }
        }
    }

    private fun parseRetry(retry: Any?): RetryConfig? {
        if (retry == null) return null
        val map = retry as Map<String, Any>
        return RetryConfig(
            maxAttempts = (map["maxAttempts"] as? Number)?.toInt() ?: 1,
            interval = Duration(map["interval"] as? String ?: "1s")
        )
    }

    private fun parseQueueRef(queue: Any?): QueueRefConfig? {
        if (queue == null) return null
        val map = queue as Map<String, Any>
        return QueueRefConfig(
            priority = map["priority"] as? String,
            queue = map["queue"] as? String
        )
    }

    private fun parseLinkOutputs(link: Any?): List<LinkOutputConfig> {
        if (link == null) return emptyList()

        return (link as List<*>).mapNotNull { output ->
            (output as? Map<String, Any>)?.let { map ->
                LinkOutputConfig(
                    name = map["name"] as? String ?: return@mapNotNull null,
                    linkId = map["linkId"] as? String ?: return@mapNotNull null,
                    role = parseLinkRole(map["role"] as? String),
                    topic = map["topic"] as? String,
                    qos = (map["qos"] as? Number)?.toInt(),
                    retain = map["retain"] as? Boolean ?: false,
                    retry = parseRetry(map["retry"]),
                    onFailure = parseOnFailure(map["onFailure"]),
                    queue = parseQueueRef(map["queue"]),
                    whenCondition = map["when"] as? String,
                    deny = map["deny"] as? String,
                    format = parseFormatSteps(map["format"])
                )
            }
        }
    }

    private fun parseInternalOutputs(internal: Any?): List<InternalOutputConfig> {
        if (internal == null) return emptyList()

        return (internal as List<*>).mapNotNull { output ->
            (output as? Map<String, Any>)?.let { map ->
                InternalOutputConfig(
                    name = map["name"] as? String ?: return@mapNotNull null,
                    type = parseInternalOutputType(map["type"] as? String) ?: return@mapNotNull null,
                    basePath = map["basePath"] as? String,
                    fileName = map["fileName"] as? String,
                    options = map["options"] as? Map<String, Any>,
                    channel = map["channel"] as? String,
                    format = parseFormatSteps(map["format"])
                )
            }
        }
    }

    private fun parseInternalOutputType(type: String?): InternalOutputType? {
        return when (type?.lowercase()) {
            "clipboard" -> InternalOutputType.clipboard
            "file" -> InternalOutputType.file
            "broadcast" -> InternalOutputType.broadcast
            "notify" -> InternalOutputType.notify
            "clipboardhistory" -> InternalOutputType.clipboardHistory
            else -> {
                LogManager.logError("CONFIG", "Unknown internal output type: $type")
                LogManager.showToast("未知的输出类型: $type")
                null
            }
        }
    }

    // ==================== Rule Parsing ====================

    private fun parseRules(rules: Any?): List<RuleConfig> {
        if (rules == null) return emptyList()

        return (rules as List<*>).mapNotNull { rule ->
            (rule as? Map<String, Any>)?.let { map ->
                val froms = parseStringOrList(map["from"])
                if (froms.isEmpty()) return@mapNotNull null
                RuleConfig(
                    name = map["name"] as? String ?: return@mapNotNull null,
                    from = froms.first(),
                    froms = froms,
                    pipeline = parsePipeline(map["pipeline"]),
                    onError = parsePipeline(map["onError"]),
                    whenCondition = map["when"] as? String,
                    deny = map["deny"] as? String
                )
            }
        }
    }

    private fun parsePipeline(pipeline: Any?): List<PipelineStep> {
        if (pipeline == null) return emptyList()

        return (pipeline as List<*>).mapNotNull { step ->
            (step as? Map<String, Any>)?.let { map ->
                PipelineStep(
                    transform = parseTransform(map["transform"]),
                    to = (map["to"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                )
            }
        }
    }

    private fun parseTransform(transform: Any?): TransformConfig? {
        if (transform == null) return null
        val map = transform as Map<String, Any>
        val rawFormat = map["format"]
        val formatSteps = if (rawFormat is List<*>) parseFormatSteps(rawFormat) else null
        val formatStr = if (rawFormat is String) rawFormat else null
        return TransformConfig(
            extract = map["extract"] as? String,
            filter = map["filter"] as? String,
            detect = map["detect"] as? String,
            format = formatStr,
            enrich = map["enrich"] as? String,
            formatSteps = formatSteps
        )
    }

    // ==================== Dead Letter Parsing ====================

    private fun parseDeadLetter(deadLetter: Any?): DeadLetterConfig {
        if (deadLetter == null) return DeadLetterConfig()

        val map = deadLetter as Map<String, Any>
        return DeadLetterConfig(
            enabled = map["enabled"] as? Boolean ?: false,
            maxRetry = (map["maxRetry"] as? Number)?.toInt() ?: 10,
            action = parsePipeline(map["action"])
        )
    }

    // ==================== Quick Settings Parsing ====================

    private fun parseQuickSettings(quickSettings: Any?): QuickSettingsConfig {
        if (quickSettings == null) return QuickSettingsConfig()

        val map = quickSettings as Map<String, Any>
        return QuickSettingsConfig(
            inputMethodSwitcher = map["inputMethodSwitcher"] as? Boolean ?: true
        )
    }
}

class ConfigLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)
