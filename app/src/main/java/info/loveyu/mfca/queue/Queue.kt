package info.loveyu.mfca.queue

/**
 * 队列接口
 */
interface Queue {
    val name: String
    val type: QueueType

    fun enqueue(item: QueueItem): Boolean
    fun dequeue(): QueueItem?
    fun peek(): QueueItem?
    fun size(): Int
    fun isEmpty(): Boolean
    fun clear()

    fun start()
    fun stop()
}

enum class QueueType {
    memory, sqlite
}

/**
 * 队列项
 */
data class QueueItem(
    val id: Long = System.currentTimeMillis(),
    val data: ByteArray,
    val priority: Int = 0,
    val metadata: Map<String, String> = emptyMap(),
    val enqueuedAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val nextAttemptAt: Long = enqueuedAt
) {
    val text: String
        get() = String(data)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as QueueItem
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * 队列消费者回调
 */
typealias QueueConsumer = (QueueItem) -> Boolean
