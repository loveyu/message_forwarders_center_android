package info.loveyu.mfca.queue

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import info.loveyu.mfca.config.BackoffType
import info.loveyu.mfca.config.SqliteQueueConfig
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

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
    private val counter = AtomicInteger(0)
    private val isProcessing = AtomicBoolean(false)
    private var consumer: QueueConsumer? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastProcessTime = 0L

    init {
        val dbPath = resolvePath(config.path)
        val dbFile = File(dbPath)
        dbFile.parentFile?.mkdirs()
        dbHelper = QueueDbHelper(context, dbFile.absolutePath)
        initializeCounter()
    }

    /**
     * 解析路径协议
     * 支持:
     * - data:// - 应用私有数据目录 (context.getExternalFilesDir)
     * - sdcard:// - 外部存储卡目录
     * - file:// - 文件系统绝对路径
     */
    private fun resolvePath(path: String): String {
        return when {
            path.startsWith("data://") -> {
                // 应用外部数据目录
                val relativePath = path.removePrefix("data://")
                val externalDir = context.getExternalFilesDir(null)
                if (externalDir != null) {
                    File(externalDir, relativePath).absolutePath
                } else {
                    File(context.filesDir, relativePath).absolutePath
                }
            }
            path.startsWith("sdcard://") -> {
                // 外部存储根目录
                val relativePath = path.removePrefix("sdcard://")
                val sdcardDir = Environment.getExternalStorageDirectory()
                File(sdcardDir, relativePath).absolutePath
            }
            path.startsWith("file://") -> {
                // 文件系统绝对路径
                path.removePrefix("file://")
            }
            else -> throw IllegalArgumentException("Unsupported path protocol: $path, must use data://, sdcard:// or file://")
        }
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
            put("next_attempt_at", item.nextAttemptAt)
            put("metadata", serializeMetadata(item.metadata))
        }

        val id = db.insert("queue_items", null, values)
        if (id != -1L) {
            counter.incrementAndGet()
            return true
        }
        return false
    }

    override fun dequeue(): QueueItem? {
        return dequeueReady(System.currentTimeMillis())
    }

    private fun dequeueReady(now: Long): QueueItem? {
        val db = dbHelper.writableDatabase

        val cursor = db.query(
            "queue_items",
            null,
            "next_attempt_at <= ?",
            arrayOf(now.toString()),
            null,
            null,
            "priority DESC, next_attempt_at ASC, enqueued_at ASC",
            "1"
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
    }

    override fun start() {
        lastProcessTime = System.currentTimeMillis()
        LogManager.logDebug("QUEUE", "SQLite queue $name started (tick-driven, interval=${config.retryInterval.value})")
    }

    override fun stop() {
        scope.cancel()
        LogManager.logDebug("QUEUE", "SQLite queue $name stopped")
    }

    /**
     * 由统一 Ticker 调用。
     * 当到达 queue 的最小处理间隔时，异步执行一轮到期任务批处理。
     */
    fun onTick() {
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < config.retryInterval.millis) return
        if (!isProcessing.compareAndSet(false, true)) return
        lastProcessTime = now

        scope.launch {
            try {
                processBatch(now)
                cleanupExpired()
            } finally {
                isProcessing.set(false)
            }
        }
    }

    fun setConsumer(consumer: QueueConsumer) {
        this.consumer = consumer
    }

    private suspend fun processBatch(now: Long) {
        repeat(config.batchSize) {
            val item = dequeueReady(now) ?: return@repeat

            try {
                val success = consumer?.invoke(item) ?: false
                if (!success) {
                    handleRetry(item)
                }
            } catch (e: Exception) {
                LogManager.logWarn("QUEUE", "Error processing item in queue $name: ${e.message}")
                handleRetry(item)
            }
        }
    }

    private fun handleRetry(item: QueueItem) {
        if (item.retryCount < config.maxRetry) {
            val nextAttemptAt = System.currentTimeMillis() + calculateBackoff(item.retryCount)
            enqueue(
                item.copy(
                    retryCount = item.retryCount + 1,
                    nextAttemptAt = nextAttemptAt
                )
            )
        } else {
            LogManager.logWarn("QUEUE", "Item exceeded max retries in queue $name, moving to dead letter")
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

    private fun cleanupExpired() {
        val cleanup = config.cleanup ?: return
        val cutoff = System.currentTimeMillis() - cleanup.maxAge.millis

        val db = dbHelper.writableDatabase
        val deleted = db.delete("queue_items", "enqueued_at < ?", arrayOf(cutoff.toString()))
        if (deleted > 0) {
            counter.addAndGet(-deleted)
            LogManager.logDebug("QUEUE", "Cleaned up $deleted expired items from $name")
        }
    }

    private fun cursorToItem(cursor: android.database.Cursor): QueueItem {
        return QueueItem(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            data = cursor.getBlob(cursor.getColumnIndexOrThrow("data")),
            priority = cursor.getInt(cursor.getColumnIndexOrThrow("priority")),
            enqueuedAt = cursor.getLong(cursor.getColumnIndexOrThrow("enqueued_at")),
            retryCount = cursor.getInt(cursor.getColumnIndexOrThrow("retry_count")),
            nextAttemptAt = cursor.getLong(cursor.getColumnIndexOrThrow("next_attempt_at")),
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
    ) : SQLiteOpenHelper(context, dbPath, null, 2) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS queue_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    data BLOB NOT NULL,
                    priority INTEGER DEFAULT 0,
                    enqueued_at INTEGER NOT NULL,
                    retry_count INTEGER DEFAULT 0,
                    next_attempt_at INTEGER NOT NULL,
                    metadata TEXT
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_queue_priority ON queue_items(priority)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_queue_enqueued ON queue_items(enqueued_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_queue_next_attempt ON queue_items(next_attempt_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE queue_items ADD COLUMN next_attempt_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE queue_items SET next_attempt_at = enqueued_at WHERE next_attempt_at = 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_queue_next_attempt ON queue_items(next_attempt_at)")
            }
        }
    }
}
