package info.loveyu.mfca.config

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.StringReader

object ConfigLoader {

    private val yaml = Yaml(LoaderOptions())

    fun loadConfig(yamlString: String): AppConfig {
        return try {
            val data = yaml.load(StringReader(yamlString)) as? Map<String, Any>
                ?: throw IllegalArgumentException("Invalid YAML format")

            AppConfig(
                version = data["version"] as? String ?: "0.1",
                links = parseLinks(data["links"]),
                inputs = parseInputs(data["inputs"]),
                queues = parseQueues(data["queues"]),
                outputs = parseOutputs(data["outputs"]),
                rules = parseRules(data["rules"]),
                deadLetter = parseDeadLetter(data["dead_letter"])
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

    // ==================== Link Parsing ====================

    private fun parseLinks(links: Any?): List<LinkConfig> {
        if (links == null) return emptyList()

        return (links as List<*>).mapNotNull { link ->
            (link as? Map<String, Any>)?.let { map ->
                LinkConfig(
                    id = map["id"] as? String ?: return@mapNotNull null,
                    dsn = map["dsn"] as? String,
                    clientId = (map["client_id"] as? String),
                    url = map["url"] as? String,
                    host = map["host"] as? String,
                    port = (map["port"] as? Number)?.toInt(),
                    reconnect = null,
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
            key = map["key"] as? String
        )
    }

    // ==================== Input Parsing ====================

    private fun parseInputs(inputs: Any?): InputsConfig {
        if (inputs == null) return InputsConfig()

        val map = inputs as Map<String, Any>
        return InputsConfig(
            http = parseHttpInputs(map["http"]),
            link = parseLinkInputs(map["link"])
        )
    }

    private fun parseHttpInputs(http: Any?): List<HttpInputConfig> {
        if (http == null) return emptyList()

        return (http as List<*>).mapNotNull { input ->
            (input as? Map<String, Any>)?.let { map ->
                HttpInputConfig(
                    name = map["name"] as? String ?: return@mapNotNull null,
                    listen = map["listen"] as? String ?: "0.0.0.0",
                    port = (map["port"] as? Number)?.toInt() ?: 8080,
                    path = map["path"] as? String ?: "/",
                    auth = parseHttpAuth(map["auth"]),
                    whenCondition = map["when"] as? String,
                    deny = map["deny"] as? String
                )
            }
        }
    }

    private fun parseHttpAuth(auth: Any?): HttpAuthConfig? {
        if (auth == null) return null
        val map = auth as Map<String, Any>
        val type = (map["type"] as? String)?.lowercase()

        return HttpAuthConfig(
            type = when (type) {
                "basic" -> HttpAuthType.basic
                "bearer" -> HttpAuthType.bearer
                "query" -> HttpAuthType.query
                else -> HttpAuthType.basic
            },
            basic = parseBasicAuth(map["basic"]),
            bearer = parseBearerAuth(map["bearer"]),
            query = parseQueryAuth(map["query"])
        )
    }

    private fun parseBasicAuth(basic: Any?): BasicAuth? {
        if (basic == null) return null
        val map = basic as Map<String, Any>
        return BasicAuth(
            username = map["username"] as? String ?: "",
            password = map["password"] as? String ?: ""
        )
    }

    private fun parseBearerAuth(bearer: Any?): BearerAuth? {
        if (bearer == null) return null
        val map = bearer as Map<String, Any>
        return BearerAuth(token = map["token"] as? String ?: "")
    }

    private fun parseQueryAuth(query: Any?): QueryAuth? {
        if (query == null) return null
        val map = query as Map<String, Any>
        return QueryAuth(
            key = map["key"] as? String ?: "",
            value = map["value"] as? String ?: ""
        )
    }

    private fun parseLinkInputs(link: Any?): List<LinkInputConfig> {
        if (link == null) return emptyList()

        return (link as List<*>).mapNotNull { input ->
            (input as? Map<String, Any>)?.let { map ->
                LinkInputConfig(
                    name = map["name"] as? String ?: return@mapNotNull null,
                    linkId = map["link_id"] as? String ?: return@mapNotNull null,
                    role = parseLinkRole(map["role"] as? String),
                    topic = map["topic"] as? String,
                    qos = (map["qos"] as? Number)?.toInt(),
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
                    batchSize = (map["batch_size"] as? Number)?.toInt() ?: 20,
                    retryInterval = Duration(map["retry_interval"] as? String ?: "5s"),
                    maxRetry = (map["max_retry"] as? Number)?.toInt() ?: 10,
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
            maxAge = Duration(map["max_age"] as? String ?: "7d")
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
                    queue = parseQueueRef(map["queue"])
                )
            }
        }
    }

    private fun parseRetry(retry: Any?): RetryConfig? {
        if (retry == null) return null
        val map = retry as Map<String, Any>
        return RetryConfig(
            maxAttempts = (map["max_attempts"] as? Number)?.toInt() ?: 1,
            interval = Duration(map["interval"] as? String ?: "1s")
        )
    }

    private fun parseQueueRef(queue: Any?): QueueRefConfig? {
        if (queue == null) return null
        val map = queue as Map<String, Any>
        return QueueRefConfig(
            priority = map["priority"] as? String,
            memoryQueue = map["memory_queue"] as? String,
            sqliteQueue = map["sqlite_queue"] as? String
        )
    }

    private fun parseLinkOutputs(link: Any?): List<LinkOutputConfig> {
        if (link == null) return emptyList()

        return (link as List<*>).mapNotNull { output ->
            (output as? Map<String, Any>)?.let { map ->
                LinkOutputConfig(
                    name = map["name"] as? String ?: return@mapNotNull null,
                    linkId = map["link_id"] as? String ?: return@mapNotNull null,
                    role = parseLinkRole(map["role"] as? String),
                    topic = map["topic"] as? String,
                    queue = parseQueueRef(map["queue"]),
                    whenCondition = map["when"] as? String,
                    deny = map["deny"] as? String
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
                    type = parseInternalOutputType(map["type"] as? String),
                    basePath = map["base_path"] as? String,
                    options = map["options"] as? Map<String, Any>,
                    channel = map["channel"] as? String
                )
            }
        }
    }

    private fun parseInternalOutputType(type: String?): InternalOutputType {
        return when (type?.lowercase()) {
            "clipboard" -> InternalOutputType.clipboard
            "file" -> InternalOutputType.file
            "broadcast" -> InternalOutputType.broadcast
            else -> InternalOutputType.clipboard
        }
    }

    // ==================== Rule Parsing ====================

    private fun parseRules(rules: Any?): List<RuleConfig> {
        if (rules == null) return emptyList()

        return (rules as List<*>).mapNotNull { rule ->
            (rule as? Map<String, Any>)?.let { map ->
                RuleConfig(
                    name = map["name"] as? String ?: return@mapNotNull null,
                    from = map["from"] as? String ?: return@mapNotNull null,
                    pipeline = parsePipeline(map["pipeline"]),
                    onError = parsePipeline(map["on_error"]),
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
        return TransformConfig(
            extract = map["extract"] as? String,
            filter = map["filter"] as? String,
            detect = map["detect"] as? String
        )
    }

    // ==================== Dead Letter Parsing ====================

    private fun parseDeadLetter(deadLetter: Any?): DeadLetterConfig {
        if (deadLetter == null) return DeadLetterConfig()

        val map = deadLetter as Map<String, Any>
        return DeadLetterConfig(
            enabled = map["enabled"] as? Boolean ?: false,
            maxRetry = (map["max_retry"] as? Number)?.toInt() ?: 10,
            action = parsePipeline(map["action"])
        )
    }
}

class ConfigLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)
