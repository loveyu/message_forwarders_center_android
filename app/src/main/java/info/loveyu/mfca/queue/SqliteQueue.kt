package info.loveyu.mfca.queue

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import info.loveyu.mfca.config.BackoffType
import info.loveyu.mfca.config.SqliteQueueConfig
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * SQLite 持久化队列实现
 */
class SqliteQueue(
    private val context: Context,
    override val name: String,
    private val config: SqliteQueueConfig
) : Queue {

    override val type: QueueType = QueueType.sqlite

    private val dbHelper: QueueDbHelper
    private val pendingQueue = ConcurrentLinkedQueue<QueueItem>()
    private val counter = AtomicInteger(0)
    private var consumer: QueueConsumer? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        val dbFile = File(config.path)
        dbFile.parentFile?.mkdirs()
        dbHelper = QueueDbHelper(context, dbFile.absolutePath)
        initializeCounter()
    }

    private fun initializeCounter() {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM queue_items", null)
        if (cursor.moveToFirst()) {
            counter.set(cursor.getInt(0))
        }
        cursor.close()
    }

    override fun enqueue(item: QueueItem): Boolean {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("data", item.data)
            put("priority", item.priority)
            put("enqueued_at", item.enqueuedAt)
            put("retry_count", item.retryCount)
            put("metadata", serializeMetadata(item.metadata))
        }

        val id = db.insert("queue_items", null, values)
        if (id != -1L) {
            counter.incrementAndGet()
            scheduleRetryIfNeeded(item)
            return true
        }
        return false
    }

    override fun dequeue(): QueueItem? {
        val db = dbHelper.writableDatabase

        val cursor = db.query(
            "queue_items",
            null,
            null,
            null,
            null,
            null,
            "priority DESC, enqueued_at ASC",
            config.batchSize.toString()
        )

        var item: QueueItem? = null
        if (cursor.moveToFirst()) {
            item = cursorToItem(cursor)
            val deleted = db.delete("queue_items", "id = ?", arrayOf(item.id.toString()))
            if (deleted > 0) {
                counter.decrementAndGet()
            }
        }
        cursor.close()

        return item
    }

    override fun peek(): QueueItem? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            "queue_items",
            null,
            null,
            null,
            null,
            null,
            "priority DESC, enqueued_at ASC",
            "1"
        )

        var item: QueueItem? = null
        if (cursor.moveToFirst()) {
            item = cursorToItem(cursor)
        }
        cursor.close()

        return item
    }

    override fun size(): Int = counter.get()

    override fun isEmpty(): Boolean = counter.get() == 0

    override fun clear() {
        val db = dbHelper.writableDatabase
        db.delete("queue_items", null, null)
        counter.set(0)
        pendingQueue.clear()
    }

    override fun start() {
        scope.launch {
            while (isActive) {
                processBatch()
                cleanupExpired()
                delay(config.retryInterval.millis)
            }
        }
        LogManager.appendLog("QUEUE", "SQLite queue $name started")
    }

    override fun stop() {
        scope.cancel()
        LogManager.appendLog("QUEUE", "SQLite queue $name stopped")
    }

    fun setConsumer(consumer: QueueConsumer) {
        this.consumer = consumer
    }

    private suspend fun processBatch() {
        repeat(config.batchSize) {
            val item = dequeue() ?: return@repeat

            try {
                val success = consumer?.invoke(item) ?: false
                if (!success) {
                    handleRetry(item)
                }
            } catch (e: Exception) {
                LogManager.appendLog("QUEUE", "Error processing item: ${e.message}")
                handleRetry(item)
            }
        }
    }

    private fun handleRetry(item: QueueItem) {
        if (item.retryCount < config.maxRetry) {
            pendingQueue.offer(item)
            val delay = calculateBackoff(item.retryCount)
            scope.launch {
                delay(delay)
                enqueue(item.copy(retryCount = item.retryCount + 1))
            }
        } else {
            LogManager.appendLog("QUEUE", "Item exceeded max retries, moving to dead letter")
            // TODO: Move to dead letter queue
        }
    }

    private fun calculateBackoff(retryCount: Int): Long {
        val backoff = config.backoff ?: return config.retryInterval.millis

        return when (backoff.type) {
            BackoffType.exponential -> {
                val delay = backoff.initial.millis * (1 shl retryCount)
                minOf(delay, backoff.max.millis)
            }
            BackoffType.linear -> {
                val delay = backoff.initial.millis * retryCount
                minOf(delay, backoff.max.millis)
            }
        }
    }

    private fun scheduleRetryIfNeeded(item: QueueItem) {
        // Already handled in handleRetry
    }

    private fun cleanupExpired() {
        val cleanup = config.cleanup ?: return
        val cutoff = System.currentTimeMillis() - cleanup.maxAge.millis

        val db = dbHelper.writableDatabase
        val deleted = db.delete("queue_items", "enqueued_at < ?", arrayOf(cutoff.toString()))
        if (deleted > 0) {
            counter.addAndGet(-deleted)
            LogManager.appendLog("QUEUE", "Cleaned up $deleted expired items from $name")
        }
    }

    private fun cursorToItem(cursor: android.database.Cursor): QueueItem {
        return QueueItem(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            data = cursor.getBlob(cursor.getColumnIndexOrThrow("data")),
            priority = cursor.getInt(cursor.getColumnIndexOrThrow("priority")),
            enqueuedAt = cursor.getLong(cursor.getColumnIndexOrThrow("enqueued_at")),
            retryCount = cursor.getInt(cursor.getColumnIndexOrThrow("retry_count")),
            metadata = deserializeMetadata(cursor.getString(cursor.getColumnIndexOrThrow("metadata")))
        )
    }

    private fun serializeMetadata(metadata: Map<String, String>): String {
        return metadata.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    private fun deserializeMetadata(data: String?): Map<String, String> {
        if (data.isNullOrEmpty()) return emptyMap()
        return data.split(";").mapNotNull { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    private inner class QueueDbHelper(
        context: Context,
        dbPath: String
    ) : SQLiteOpenHelper(context, dbPath, null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS queue_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    data BLOB NOT NULL,
                    priority INTEGER DEFAULT 0,
                    enqueued_at INTEGER NOT NULL,
                    retry_count INTEGER DEFAULT 0,
                    metadata TEXT
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_queue_priority ON queue_items(priority)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_queue_enqueued ON queue_items(enqueued_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS queue_items")
            onCreate(db)
        }
    }
}
