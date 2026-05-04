package info.loveyu.mfca.clipboard

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest

class ClipboardHistoryDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "clipboard_history.db"
        const val DATABASE_VERSION = 1
        const val TABLE_NAME = "clipboard_records"

        private val _changeVersion = MutableStateFlow(0)
        val changeVersion: StateFlow<Int> = _changeVersion

        const val COL_ID = "id"
        const val COL_CONTENT_HASH = "contentHash"
        const val COL_CONTENT = "content"
        const val COL_CONTENT_TYPE = "contentType"
        const val COL_PINNED = "pinned"
        const val COL_PINNED_AT = "pinnedAt"
        const val COL_NOTIFICATION_PINNED = "notificationPinned"
        const val COL_NOTIFICATION_ID = "notificationId"
        const val COL_CREATED_AT = "createdAt"
        const val COL_UPDATED_AT = "updatedAt"

        const val DEFAULT_MAX_RECORDS = 1000

        private val sha1Digest by lazy { MessageDigest.getInstance("SHA-1") }

        /**
         * 记录 clipboardNew 过滤函数最近一次通过（返回 true）的时间戳
         * Key: contentHash, Value: 通过时的 System.currentTimeMillis()
         *
         * 仅在过滤通过时更新，被拒绝的消息不会刷新此时间戳，
         * 避免被拒绝消息的历史写入（updatedAt 刷新）导致窗口无限延长。
         */
        private val lastPassedTime = mutableMapOf<String, Long>()

        /**
         * 获取距上次更新（通过过滤）的秒数
         * 用于 clipboardUpdateBefore 模板函数
         *
         * @return 距上次通过的秒数，不存在则返回 -1
         */
        fun getSecondsSinceLastUpdate(context: Context, text: String): Long {
            if (text.isEmpty()) return -1L
            val hash = sha1(text)
            val now = System.currentTimeMillis()

            // 优先检查内存缓存
            val lastPassed = lastPassedTime[hash]
            if (lastPassed != null) {
                return (now - lastPassed) / 1000L
            }

            // 回退到数据库查询（应用重启后缓存丢失的场景）
            val helper = ClipboardHistoryDbHelper(context.applicationContext)
            return try {
                val db = helper.readableDatabase
                val cursor = db.query(
                    TABLE_NAME,
                    arrayOf(COL_UPDATED_AT),
                    "$COL_CONTENT_HASH = ?",
                    arrayOf(hash),
                    null, null, null, "1"
                )
                if (!cursor.moveToFirst()) {
                    cursor.close()
                    -1L // 不在历史中
                } else {
                    val updatedAt = cursor.getLong(0)
                    cursor.close()
                    (now - updatedAt) / 1000L
                }
            } catch (_: Exception) {
                -1L
            } finally {
                helper.close()
            }
        }

        /**
         * 更新 lastPassedTime 缓存，记录当前时间为通过时间
         */
        fun updateLastPassedTime(text: String) {
            if (text.isEmpty()) return
            val hash = sha1(text)
            lastPassedTime[hash] = System.currentTimeMillis()
        }

        /**
         * 检查内容是否为新内容，或者距离上次通过过滤已超过指定时长
         * 用于 clipboardNew 过滤函数，确保在写入历史之前调用
         *
         * 判断逻辑：
         *   1. 优先检查内存缓存（lastPassedTime）：仅记录过滤通过的时间，
         *      被拒绝消息不会刷新，确保时间窗口基于上次实际通过的时间
         *   2. 回退到数据库查询：用于应用重启后缓存丢失的场景
         *
         * @return true 表示内容是新的（未在历史中）或者距上次通过已超过 maxAgeMs 毫秒
         */
        fun isNotRecentlyUpdated(context: Context, text: String, maxAgeMs: Long = 10_000L): Boolean {
            if (text.isEmpty()) return false
            val hash = sha1(text)
            val now = System.currentTimeMillis()

            // 优先检查内存缓存：仅当过滤通过时才更新，避免被拒绝消息刷新时间窗口
            val lastPassed = lastPassedTime[hash]
            if (lastPassed != null && (now - lastPassed) <= maxAgeMs) {
                return false // 近期已通过过滤，拒绝
            }

            // 回退到数据库查询（应用重启后缓存丢失的场景）
            val helper = ClipboardHistoryDbHelper(context.applicationContext)
            val result = try {
                val db = helper.readableDatabase
                val cursor = db.query(
                    TABLE_NAME,
                    arrayOf(COL_UPDATED_AT),
                    "$COL_CONTENT_HASH = ?",
                    arrayOf(hash),
                    null, null, null, "1"
                )
                if (!cursor.moveToFirst()) {
                    cursor.close()
                    true // 不在历史中，属于新内容
                } else {
                    val updatedAt = cursor.getLong(0)
                    cursor.close()
                    (now - updatedAt) > maxAgeMs // 超过时间窗口才认为是新内容
                }
            } catch (e: Exception) {
                true // 查询失败时放行
            } finally {
                helper.close()
            }

            // 过滤通过时更新缓存
            if (result) {
                lastPassedTime[hash] = now
                // 简单清理：超过 1000 条时移除过期条目
                if (lastPassedTime.size > 1000) {
                    val expiredKeys = lastPassedTime.entries
                        .filter { (now - it.value) > maxAgeMs * 2 }
                        .map { it.key }
                    expiredKeys.forEach { lastPassedTime.remove(it) }
                }
            }
            return result
        }

        @Synchronized
        fun sha1(text: String): String {
            val digest = sha1Digest
            digest.reset()
            val hashBytes = digest.digest(text.toByteArray())
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        private val htmlTagRegex = Regex("</[a-zA-Z][a-zA-Z0-9]*>")
        private val markdownPatterns = listOf(
            Regex("^#{1,6}\\s"),
            Regex("\\*\\*.*\\*\\*"),
            Regex("\\[.+\\]\\(.+\\)"),
            Regex("^[-*+]\\s", RegexOption.MULTILINE),
            Regex("^>\\s", RegexOption.MULTILINE),
            Regex("^```"),
            Regex("^\\|.+.\\|")
        )

        fun detectContentType(content: String): String {
            val trimmed = content.trimStart()
            if (trimmed.startsWith('<')) {
                if (htmlTagRegex.containsMatchIn(trimmed) ||
                    trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
                    trimmed.startsWith("<html", ignoreCase = true)
                ) {
                    return "html"
                }
            }
            if (isJsonContent(trimmed)) return "json"
            var matchCount = 0
            for (pattern in markdownPatterns) {
                if (pattern.containsMatchIn(content)) matchCount++
            }
            if (matchCount >= 2) return "markdown"
            if (isYamlContent(trimmed)) return "yaml"
            return "text"
        }

        private fun isJsonContent(content: String): Boolean {
            val trimmed = content.trim()
            if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return false
            return try {
                if (trimmed.startsWith('{')) org.json.JSONObject(trimmed) else org.json.JSONArray(trimmed)
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun isYamlContent(content: String): Boolean {
            val trimmed = content.trim()
            if (trimmed.startsWith('{') || trimmed.startsWith('[') || trimmed.startsWith('<')) return false
            val hasSeparator = trimmed.startsWith("---")
            val keyCount = Regex("^[a-zA-Z_][a-zA-Z0-9_.-]*:\\s", RegexOption.MULTILINE)
                .findAll(trimmed).count()
            return hasSeparator && keyCount >= 1 || keyCount >= 3
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CONTENT_HASH TEXT NOT NULL,
                $COL_CONTENT TEXT NOT NULL,
                $COL_CONTENT_TYPE TEXT NOT NULL DEFAULT 'text',
                $COL_PINNED INTEGER DEFAULT 0,
                $COL_PINNED_AT INTEGER,
                $COL_NOTIFICATION_PINNED INTEGER DEFAULT 0,
                $COL_NOTIFICATION_ID INTEGER,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_clipboard_hash ON $TABLE_NAME($COL_CONTENT_HASH)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_clipboard_updated_at ON $TABLE_NAME($COL_UPDATED_AT DESC)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_clipboard_pinned ON $TABLE_NAME($COL_PINNED DESC, $COL_PINNED_AT DESC)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun insertOrUpdate(content: String, contentType: String? = null): Long {
        if (content.isEmpty()) return -1L
        val db = writableDatabase
        val hash = sha1(content)
        val now = System.currentTimeMillis()
        val detectedType = contentType ?: detectContentType(content)

        val existing = db.query(
            TABLE_NAME,
            arrayOf(COL_ID),
            "$COL_CONTENT_HASH = ?",
            arrayOf(hash),
            null, null, null, "1"
        )
        val existingId = if (existing.moveToFirst()) existing.getLong(0) else null
        existing.close()

        if (existingId != null) {
            val values = ContentValues().apply {
                put(COL_UPDATED_AT, now)
            }
            val rows = db.update(
                TABLE_NAME,
                values,
                "$COL_CONTENT_HASH = ?",
                arrayOf(hash)
            )
            if (rows > 0) {
                _changeVersion.value += 1
            }
            return if (rows > 0) existingId else -1L
        } else {
            val values = ContentValues().apply {
                put(COL_CONTENT_HASH, hash)
                put(COL_CONTENT, content)
                put(COL_CONTENT_TYPE, detectedType)
                put(COL_PINNED, 0)
                put(COL_NOTIFICATION_PINNED, 0)
                put(COL_CREATED_AT, now)
                put(COL_UPDATED_AT, now)
            }
            val id = db.insert(TABLE_NAME, null, values)
            if (id > 0) {
                _changeVersion.value += 1
                cleanupOldRecords()
            }
            return id
        }
    }

    fun query(
        keyword: String? = null,
        limit: Int = 200,
        offset: Int = 0
    ): List<ClipboardRecord> {
        val db = readableDatabase
        val selection = if (!keyword.isNullOrBlank()) "$COL_CONTENT LIKE ?" else null
        val selectionArgs = if (!keyword.isNullOrBlank()) arrayOf("%$keyword%") else null
        val orderBy = "$COL_PINNED DESC, $COL_PINNED_AT DESC, $COL_UPDATED_AT DESC"

        val cursor = db.query(
            TABLE_NAME,
            null,
            selection,
            selectionArgs,
            null, null,
            orderBy,
            "$limit OFFSET $offset"
        )
        return cursorToList(cursor)
    }

    fun queryById(id: Long): ClipboardRecord? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME, null,
            "$COL_ID = ?", arrayOf(id.toString()),
            null, null, null
        )
        val records = cursorToList(cursor)
        return records.firstOrNull()
    }

    fun count(keyword: String? = null): Int {
        val db = readableDatabase
        val selection = if (!keyword.isNullOrBlank()) "$COL_CONTENT LIKE ?" else null
        val selectionArgs = if (!keyword.isNullOrBlank()) arrayOf("%$keyword%") else null

        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_NAME${if (selection != null) " WHERE $selection" else ""}",
            selectionArgs
        )
        var result = 0
        if (cursor.moveToFirst()) {
            result = cursor.getInt(0)
        }
        cursor.close()
        return result
    }

    fun updatePinned(id: Long, pinned: Boolean): Boolean {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_PINNED, if (pinned) 1 else 0)
            put(COL_PINNED_AT, if (pinned) now else null as Long?)
            put(COL_UPDATED_AT, now)
        }
        val rows = db.update(TABLE_NAME, values, "$COL_ID = ?", arrayOf(id.toString()))
        if (rows > 0) _changeVersion.value += 1
        return rows > 0
    }

    fun updateNotificationPinned(
        id: Long,
        notificationPinned: Boolean,
        notificationId: Int?
    ): Boolean {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_NOTIFICATION_PINNED, if (notificationPinned) 1 else 0)
            put(COL_NOTIFICATION_ID, notificationId)
            put(COL_UPDATED_AT, now)
        }
        val rows = db.update(TABLE_NAME, values, "$COL_ID = ?", arrayOf(id.toString()))
        if (rows > 0) _changeVersion.value += 1
        return rows > 0
    }

    fun deleteById(id: Long): Boolean {
        val db = writableDatabase
        val rows = db.delete(TABLE_NAME, "$COL_ID = ?", arrayOf(id.toString()))
        if (rows > 0) _changeVersion.value += 1
        return rows > 0
    }

    fun deleteAll(): Int {
        val db = writableDatabase
        val deleted = db.delete(TABLE_NAME, null, null)
        if (deleted > 0) _changeVersion.value += 1
        return deleted
    }

    fun cleanupOldRecords(maxRecords: Int = DEFAULT_MAX_RECORDS): Int {
        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_NAME WHERE $COL_PINNED = 0",
            null
        )
        var unpinnedCount = 0
        if (cursor.moveToFirst()) {
            unpinnedCount = cursor.getInt(0)
        }
        cursor.close()

        if (unpinnedCount <= maxRecords) return 0

        val toDelete = unpinnedCount - maxRecords
        val deleted = db.delete(
            TABLE_NAME,
            "$COL_ID IN (SELECT $COL_ID FROM $TABLE_NAME WHERE $COL_PINNED = 0 ORDER BY $COL_UPDATED_AT DESC LIMIT -1 OFFSET $maxRecords)",
            null
        )
        if (deleted > 0) {
            LogManager.logDebug("CLIPBOARD", "Cleaned up $deleted old clipboard history records")
        }
        return deleted
    }

    fun getNotificationPinnedRecords(): List<ClipboardRecord> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME, null,
            "$COL_NOTIFICATION_PINNED = 1",
            null, null, null,
            "$COL_UPDATED_AT DESC"
        )
        return cursorToList(cursor)
    }

    private fun cursorToList(cursor: Cursor): List<ClipboardRecord> {
        val records = mutableListOf<ClipboardRecord>()
        while (cursor.moveToNext()) {
            records.add(cursorToRecord(cursor))
        }
        cursor.close()
        return records
    }

    private fun cursorToRecord(cursor: Cursor): ClipboardRecord {
        val pinnedAtIdx = cursor.getColumnIndexOrThrow(COL_PINNED_AT)
        val notificationIdIdx = cursor.getColumnIndexOrThrow(COL_NOTIFICATION_ID)
        return ClipboardRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            contentHash = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT_HASH)),
            content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)),
            contentType = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT_TYPE)),
            pinned = cursor.getInt(cursor.getColumnIndexOrThrow(COL_PINNED)) == 1,
            pinnedAt = if (cursor.isNull(pinnedAtIdx)) null else cursor.getLong(pinnedAtIdx),
            notificationPinned = cursor.getInt(cursor.getColumnIndexOrThrow(COL_NOTIFICATION_PINNED)) == 1,
            notificationId = if (cursor.isNull(notificationIdIdx)) null else cursor.getInt(notificationIdIdx),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT))
        )
    }
}
