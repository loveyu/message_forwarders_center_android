package info.loveyu.mfca.queue

import android.content.Context
import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.util.LogManager
import java.lang.ref.WeakReference

/**
 * 队列管理器
 */
object QueueManager {

    private val queues = mutableMapOf<String, Queue>()
    private var contextRef: WeakReference<Context>? = null

    fun initialize(ctx: Context, config: AppConfig) {
        clear()
        contextRef = WeakReference(ctx.applicationContext)

        // Initialize memory queues
        config.queues.memory.forEach { (name, memoryConfig) ->
            queues[name] = MemoryQueue(name, memoryConfig)
            LogManager.logDebug("QUEUE", "Registered memory queue: $name (capacity: ${memoryConfig.capacity})")
        }

        // Initialize SQLite queues
        config.queues.sqlite.forEach { (name, sqliteConfig) ->
            val ctx = contextRef?.get() ?: return@forEach
            val queue = SqliteQueue(ctx, name, sqliteConfig)
            queues[name] = queue
            LogManager.logDebug("QUEUE", "Registered SQLite queue: $name (path: ${sqliteConfig.path})")
        }
    }

    fun getQueue(name: String): Queue? = queues[name]

    fun getMemoryQueue(name: String): MemoryQueue? = queues[name] as? MemoryQueue

    fun getSqliteQueue(name: String): SqliteQueue? = queues[name] as? SqliteQueue

    fun startAll() {
        queues.values.forEach { queue ->
            try {
                queue.start()
            } catch (e: Exception) {
                LogManager.logError("QUEUE", "Failed to start ${queue.name}: ${e.message}")
            }
        }
    }

    fun stopAll() {
        queues.values.forEach { queue ->
            try {
                queue.stop()
            } catch (e: Exception) {
                LogManager.logWarn("QUEUE", "Error stopping ${queue.name}: ${e.message}")
            }
        }
    }

    fun clear() {
        stopAll()
        queues.clear()
    }

    /**
     * 统一 Ticker 调用：驱动 SqliteQueue 检查处理。
     * MemoryQueue 已是 Channel 事件驱动，无需 tick。
     */
    fun onTick() {
        queues.values.forEach { queue ->
            if (queue is SqliteQueue) {
                try {
                    queue.onTick()
                } catch (e: Exception) {
                    LogManager.logError("QUEUE", "Error in onTick for ${queue.name}: ${e.message}")
                }
            }
        }
    }

    fun getAllQueues(): Map<String, Queue> = queues.toMap()

    fun getQueueStats(): Map<String, QueueStats> {
        return queues.mapValues { (name, queue) ->
            QueueStats(
                name = name,
                type = queue.type,
                size = queue.size(),
                isEmpty = queue.isEmpty()
            )
        }
    }
}

data class QueueStats(
    val name: String,
    val type: QueueType,
    val size: Int,
    val isEmpty: Boolean
)
