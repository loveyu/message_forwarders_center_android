package info.loveyu.mfca.queue

/**
 * 队列接口
 */
interface Queue {
    val name: String
    val type: QueueType

    fun enqueue(item: QueueItem): Boolean
    fun dequeue(): QueueItem?
    /** 从队列中取出第一个 tag 在 [tags] 中的就绪项（tags 为空表示不过滤）*/
    fun dequeueByTag(tags: List<String>): QueueItem? = dequeue()
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
    val headers: Map<String, String> = emptyMap(),
    val enqueuedAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val nextAttemptAt: Long = enqueuedAt,
    /** 路由标签，用于 FailQueueInput 按 idType 订阅 */
    val tag: String = ""
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
