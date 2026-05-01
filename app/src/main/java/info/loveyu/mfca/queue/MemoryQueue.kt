package info.loveyu.mfca.queue

import info.loveyu.mfca.config.MemoryQueueConfig
import info.loveyu.mfca.config.OverflowStrategy
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内存队列实现
 * 使用 Channel 事件驱动，空闲时零唤醒
 */
class MemoryQueue(
    override val name: String,
    private val config: MemoryQueueConfig
) : Queue {

    override val type: QueueType = QueueType.memory

    private val queue = ConcurrentLinkedQueue<QueueItem>()
    private val counter = AtomicInteger(0)
    private val workers = mutableListOf<Job>()

    // 事件通道：有新元素入队时发送信号，worker 阻塞等待
    private var wakeSignal = Channel<Unit>(Channel.CONFLATED)

    private var consumer: QueueConsumer? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun enqueue(item: QueueItem): Boolean {
        synchronized(queue) {
            if (counter.get() >= config.capacity) {
                when (config.overflow) {
                    OverflowStrategy.dropOldest -> {
                        queue.poll()
                        queue.offer(item)
                    }
                    OverflowStrategy.dropNew -> {
                        LogManager.logWarn("QUEUE", "Memory queue $name dropped item (overflow)")
                        return false
                    }
                    OverflowStrategy.block -> {
                        queue.offer(item)
                        counter.incrementAndGet()
                        wakeSignal.trySend(Unit)
                        return true
                    }
                }
            } else {
                queue.offer(item)
                counter.incrementAndGet()
            }
            wakeSignal.trySend(Unit)
            return true
        }
    }

    override fun dequeue(): QueueItem? {
        val item = queue.poll()
        if (item != null) {
            counter.decrementAndGet()
        }
        return item
    }

    override fun dequeueByTag(tags: List<String>): QueueItem? {
        if (tags.isEmpty()) return dequeue()
        synchronized(queue) {
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (item.tag in tags) {
                    iterator.remove()
                    counter.decrementAndGet()
                    return item
                }
            }
        }
        return null
    }

    override fun peek(): QueueItem? = queue.peek()

    override fun size(): Int = counter.get()

    override fun isEmpty(): Boolean = counter.get() == 0

    override fun clear() {
        synchronized(queue) {
            queue.clear()
            counter.set(0)
        }
    }

    override fun start() {
        repeat(config.workers) { workerId ->
            val worker = scope.launch {
                while (isActive) {
                    val item = dequeue()
                    if (item != null) {
                        try {
                            val success = consumer?.invoke(item) ?: false
                            if (!success) {
                                enqueue(item.copy(retryCount = item.retryCount + 1))
                            }
                        } catch (e: Exception) {
                            LogManager.logWarn("QUEUE", "Worker $workerId error in queue $name: ${e.message}")
                            enqueue(item.copy(retryCount = item.retryCount + 1))
                        }
                    } else {
                        // 队列为空 → 阻塞等待信号，不再轮询
                        try {
                            wakeSignal.receive()
                        } catch (_: Exception) {
                            // Channel 关闭或协程取消
                        }
                    }
                }
            }
            workers.add(worker)
        }
        LogManager.logDebug("QUEUE", "Memory queue $name started with ${config.workers} workers")
    }

    override fun stop() {
        workers.forEach { it.cancel() }
        workers.clear()
        wakeSignal.close()
        // 重建 Channel 以支持后续可能的 start() 调用
        wakeSignal = Channel(Channel.CONFLATED)
        LogManager.logDebug("QUEUE", "Memory queue $name stopped")
    }

    fun setConsumer(consumer: QueueConsumer) {
        this.consumer = consumer
    }
}
