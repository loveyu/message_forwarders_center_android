package info.loveyu.mfca.config.models

data class QueuesConfig(
    val memory: Map<String, MemoryQueueConfig> = emptyMap(),
    val sqlite: Map<String, SqliteQueueConfig> = emptyMap()
)

data class MemoryQueueConfig(
    val capacity: Int = 1000,
    val workers: Int = 1,
    val overflow: OverflowStrategy = OverflowStrategy.dropOldest,
    val retryInterval: Duration = Duration("5s"),
    val maxRetry: Int = 10,
    val backoff: BackoffConfig? = null
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
