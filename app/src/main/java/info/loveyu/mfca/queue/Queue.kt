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

    /** 设置消费者回调，由队列工作线程或 tick 驱动调用 */
    fun setConsumer(consumer: QueueConsumer)
}

enum class QueueType {
    memory, sqlite
}

/**
 * 队列项
 *
 * isDeadLetter: 死信标记。标记为 true 的消息不允许再次入队，队列消费时也会跳过 queue/onFailureQueue 逻辑。
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
    val tag: String = "",
    val isDeadLetter: Boolean = false
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
 * 队列消费者回调（挂起函数，支持异步等待结果）
 * 返回 true 表示处理成功，false 触发队列层重试逻辑
 */
typealias QueueConsumer = suspend (QueueItem) -> Boolean
