package info.loveyu.mfca.queue

import info.loveyu.mfca.config.MemoryQueueConfig
import info.loveyu.mfca.config.OverflowStrategy
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内存队列实现
 */
class MemoryQueue(
    override val name: String,
    private val config: MemoryQueueConfig
) : Queue {

    override val type: QueueType = QueueType.memory

    private val queue = ConcurrentLinkedQueue<QueueItem>()
    private val counter = AtomicInteger(0)
    private val workers = mutableListOf<Job>()

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
                        LogManager.appendLog("QUEUE", "Memory queue $name dropped item (overflow)")
                        return false
                    }
                    OverflowStrategy.block -> {
                        // For blocking, we just add anyway - consumer should handle backpressure
                        queue.offer(item)
                        counter.incrementAndGet()
                        notifyWorkers()
                        return true
                    }
                }
            } else {
                queue.offer(item)
                counter.incrementAndGet()
            }

            notifyWorkers()
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
                                // Re-enqueue on failure
                                enqueue(item.copy(retryCount = item.retryCount + 1))
                            }
                        } catch (e: Exception) {
                            LogManager.appendLog("QUEUE", "Worker $workerId error: ${e.message}")
                            enqueue(item.copy(retryCount = item.retryCount + 1))
                        }
                    } else {
                        // Wait for new items
                        delay(100)
                    }
                }
            }
            workers.add(worker)
        }
        LogManager.appendLog("QUEUE", "Memory queue $name started with ${config.workers} workers")
    }

    override fun stop() {
        workers.forEach { it.cancel() }
        workers.clear()
        LogManager.appendLog("QUEUE", "Memory queue $name stopped")
    }

    fun setConsumer(consumer: QueueConsumer) {
        this.consumer = consumer
    }

    private fun notifyWorkers() {
        // Workers will automatically pick up new items
    }
}
