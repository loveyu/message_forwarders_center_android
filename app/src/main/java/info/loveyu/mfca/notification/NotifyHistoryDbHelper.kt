package info.loveyu.mfca.notification

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 通知历史数据库帮助类
 * 存储通过 NotifyOutput 发送的通知记录
 */
class NotifyHistoryDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "notify_history.db"
        const val DATABASE_VERSION = 1
        const val TABLE_NAME = "notify_records"

        // 列名
        const val COL_ID = "id"
        const val COL_NOTIFY_ID = "notifyId"
        const val COL_TITLE = "title"
        const val COL_CONTENT = "content"
        const val COL_RAW_DATA = "rawData"
        const val COL_OUTPUT_NAME = "outputName"
        const val COL_CHANNEL = "channel"
        const val COL_TAG = "tag"
        const val COL_GROUP = "group"
        const val COL_ICON_URL = "iconUrl"
        const val COL_SOURCE_RULE = "sourceRule"
        const val COL_SOURCE_INPUT = "sourceInput"
        const val COL_POPUP = "popup"
        const val COL_PERSISTENT = "persistent"
        const val COL_CREATED_AT = "createdAt"

        // 默认最大保留记录数
        const val DEFAULT_MAX_RECORDS = 1000
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NOTIFY_ID INTEGER NOT NULL,
                $COL_TITLE TEXT NOT NULL,
                $COL_CONTENT TEXT NOT NULL,
                $COL_RAW_DATA TEXT,
                $COL_OUTPUT_NAME TEXT NOT NULL,
                $COL_CHANNEL TEXT NOT NULL,
                $COL_TAG TEXT,
                $COL_GROUP TEXT,
                $COL_ICON_URL TEXT,
                $COL_SOURCE_RULE TEXT,
                $COL_SOURCE_INPUT TEXT,
                $COL_POPUP INTEGER DEFAULT 0,
                $COL_PERSISTENT INTEGER DEFAULT 0,
                $COL_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notify_created_at ON $TABLE_NAME($COL_CREATED_AT DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notify_output_name ON $TABLE_NAME($COL_OUTPUT_NAME)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notify_source_rule ON $TABLE_NAME($COL_SOURCE_RULE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notify_channel ON $TABLE_NAME($COL_CHANNEL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    /**
     * 插入一条通知记录
     */
    fun insert(record: NotifyRecord): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NOTIFY_ID, record.notifyId)
            put(COL_TITLE, record.title)
            put(COL_CONTENT, record.content)
            put(COL_RAW_DATA, record.rawData)
            put(COL_OUTPUT_NAME, record.outputName)
            put(COL_CHANNEL, record.channel)
            put(COL_TAG, record.tag)
            put(COL_GROUP, record.group)
            put(COL_ICON_URL, record.iconUrl)
            put(COL_SOURCE_RULE, record.sourceRule)
            put(COL_SOURCE_INPUT, record.sourceInput)
            put(COL_POPUP, if (record.popup) 1 else 0)
            put(COL_PERSISTENT, if (record.persistent) 1 else 0)
            put(COL_CREATED_AT, record.createdAt)
        }
        return db.insert(TABLE_NAME, null, values)
    }

    /**
     * 查询通知记录（分页，按时间倒序）
     */
    fun query(
        keyword: String? = null,
        sourceRule: String? = null,
        outputName: String? = null,
        timeRange: TimeRange = TimeRange.ALL,
        limit: Int = 100,
        offset: Int = 0
    ): List<NotifyRecord> {
        val db = readableDatabase
        val selection = buildSelection(keyword, sourceRule, outputName, timeRange)
        val selectionArgs = buildSelectionArgs(keyword, sourceRule, outputName, timeRange)

        val cursor = db.query(
            TABLE_NAME,
            null,
            selection,
            selectionArgs,
            null, null,
            "$COL_CREATED_AT DESC",
            "$limit OFFSET $offset"
        )
        return cursorToList(cursor)
    }

    /**
     * 根据 ID 查询单条记录
     */
    fun queryById(id: Long): NotifyRecord? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME, null,
            "$COL_ID = ?", arrayOf(id.toString()),
            null, null, null
        )
        val records = cursorToList(cursor)
        return records.firstOrNull()
    }

    /**
     * 根据 notifyId 查询记录（用于通知点击定位）
     */
    fun queryByNotifyId(notifyId: Int): NotifyRecord? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME, null,
            "$COL_NOTIFY_ID = ?", arrayOf(notifyId.toString()),
            null, null,
            "$COL_CREATED_AT DESC",
            "1"
        )
        val records = cursorToList(cursor)
        return records.firstOrNull()
    }

    /**
     * 查询指定 notifyId 记录在排序结果中的位置（用于列表定位）
     */
    fun getPositionByNotifyId(notifyId: Int, keyword: String? = null): Int {
        val db = readableDatabase
        // 先查到记录的 createdAt
        val target = queryByNotifyId(notifyId) ?: return -1
        // 统计 createdAt 大于目标记录的条数，即为目标位置
        val selection = buildSelection(keyword, null, null, TimeRange.ALL)
        val fullSelection = if (selection.isNullOrEmpty()) {
            "$COL_CREATED_AT > ?"
        } else {
            "$selection AND $COL_CREATED_AT > ?"
        }
        val baseArgs = buildSelectionArgs(keyword, null, null, TimeRange.ALL)
        val args = baseArgs.toList() + target.createdAt.toString()

        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_NAME WHERE $fullSelection",
            args.toTypedArray()
        )
        var position = -1
        if (cursor.moveToFirst()) {
            position = cursor.getInt(0)
        }
        cursor.close()
        return position
    }

    /**
     * 查询总记录数
     */
    fun count(
        keyword: String? = null,
        sourceRule: String? = null,
        outputName: String? = null,
        timeRange: TimeRange = TimeRange.ALL
    ): Int {
        val db = readableDatabase
        val selection = buildSelection(keyword, sourceRule, outputName, timeRange)
        val selectionArgs = buildSelectionArgs(keyword, sourceRule, outputName, timeRange)

        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_NAME${if (selection.isNullOrEmpty()) "" else " WHERE $selection"}",
            selectionArgs
        )
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return count
    }

    /**
     * 获取去重的来源规则列表（用于筛选下拉选项）
     */
    fun getDistinctSourceRules(): List<String> {
        val db = readableDatabase
        val cursor = db.query(
            true, TABLE_NAME,
            arrayOf(COL_SOURCE_RULE),
            "$COL_SOURCE_RULE IS NOT NULL AND $COL_SOURCE_RULE != ''",
            null, null, null,
            COL_SOURCE_RULE, null
        )
        val result = mutableListOf<String>()
        while (cursor.moveToNext()) {
            result.add(cursor.getString(0))
        }
        cursor.close()
        return result
    }

    /**
     * 获取去重的输出名称列表（用于筛选下拉选项）
     */
    fun getDistinctOutputNames(): List<String> {
        val db = readableDatabase
        val cursor = db.query(
            true, TABLE_NAME,
            arrayOf(COL_OUTPUT_NAME),
            null, null, null, null,
            COL_OUTPUT_NAME, null
        )
        val result = mutableListOf<String>()
        while (cursor.moveToNext()) {
            result.add(cursor.getString(0))
        }
        cursor.close()
        return result
    }

    /**
     * 删除单条记录
     */
    fun deleteById(id: Long): Boolean {
        val db = writableDatabase
        return db.delete(TABLE_NAME, "$COL_ID = ?", arrayOf(id.toString())) > 0
    }

    /**
     * 清空所有记录
     */
    fun deleteAll(): Int {
        val db = writableDatabase
        return db.delete(TABLE_NAME, null, null)
    }

    /**
     * 自动清理，保留最近 maxRecords 条记录
     */
    fun cleanupOldRecords(maxRecords: Int = DEFAULT_MAX_RECORDS): Int {
        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_NAME", null
        )
        var total = 0
        if (cursor.moveToFirst()) {
            total = cursor.getInt(0)
        }
        cursor.close()

        if (total <= maxRecords) return 0

        val toDelete = total - maxRecords
        val deleted = db.delete(
            TABLE_NAME,
            "$COL_ID IN (SELECT $COL_ID FROM $TABLE_NAME ORDER BY $COL_CREATED_AT DESC LIMIT -1 OFFSET $maxRecords)",
            null
        )
        return deleted
    }

    private fun buildSelection(
        keyword: String?,
        sourceRule: String?,
        outputName: String?,
        timeRange: TimeRange
    ): String? {
        val conditions = mutableListOf<String>()

        if (!keyword.isNullOrBlank()) {
            conditions.add("($COL_TITLE LIKE ? OR $COL_CONTENT LIKE ? OR $COL_RAW_DATA LIKE ?)")
        }
        if (!sourceRule.isNullOrBlank()) {
            conditions.add("$COL_SOURCE_RULE = ?")
        }
        if (!outputName.isNullOrBlank()) {
            conditions.add("$COL_OUTPUT_NAME = ?")
        }
        val timeCutoff = timeRange.effectiveCutoff()
        if (timeCutoff > 0) {
            conditions.add("$COL_CREATED_AT >= ?")
        }

        return conditions.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
    }

    private fun buildSelectionArgs(
        keyword: String?,
        sourceRule: String?,
        outputName: String?,
        timeRange: TimeRange
    ): Array<String> {
        val args = mutableListOf<String>()

        if (!keyword.isNullOrBlank()) {
            val pattern = "%$keyword%"
            args.add(pattern)
            args.add(pattern)
            args.add(pattern)
        }
        if (!sourceRule.isNullOrBlank()) {
            args.add(sourceRule)
        }
        if (!outputName.isNullOrBlank()) {
            args.add(outputName)
        }
        val timeCutoff = timeRange.effectiveCutoff()
        if (timeCutoff > 0) {
            args.add(timeCutoff.toString())
        }

        return args.toTypedArray()
    }

    private fun cursorToList(cursor: Cursor): List<NotifyRecord> {
        val records = mutableListOf<NotifyRecord>()
        while (cursor.moveToNext()) {
            records.add(cursorToRecord(cursor))
        }
        cursor.close()
        return records
    }

    private fun cursorToRecord(cursor: Cursor): NotifyRecord {
        return NotifyRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            notifyId = cursor.getInt(cursor.getColumnIndexOrThrow(COL_NOTIFY_ID)),
            title = cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE)),
            content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)),
            rawData = cursor.getString(cursor.getColumnIndexOrThrow(COL_RAW_DATA)),
            outputName = cursor.getString(cursor.getColumnIndexOrThrow(COL_OUTPUT_NAME)),
            channel = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHANNEL)),
            tag = cursor.getString(cursor.getColumnIndexOrThrow(COL_TAG)),
            group = cursor.getString(cursor.getColumnIndexOrThrow(COL_GROUP)),
            iconUrl = cursor.getString(cursor.getColumnIndexOrThrow(COL_ICON_URL)),
            sourceRule = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE_RULE)),
            sourceInput = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE_INPUT)),
            popup = cursor.getInt(cursor.getColumnIndexOrThrow(COL_POPUP)) == 1,
            persistent = cursor.getInt(cursor.getColumnIndexOrThrow(COL_PERSISTENT)) == 1,
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT))
        )
    }
}

/**
 * 时间范围筛选
 */
enum class TimeRange(val cutoff: Long) {
    ALL(0),
    TODAY(0),  // 动态计算
    WEEK(0),   // 动态计算
    MONTH(0);  // 动态计算

    companion object {
        fun todayCutoff(): Long {
            return System.currentTimeMillis() - (System.currentTimeMillis() % 86400000L)
        }

        fun weekCutoff(): Long {
            return System.currentTimeMillis() - 7 * 86400000L
        }

        fun monthCutoff(): Long {
            return System.currentTimeMillis() - 30 * 86400000L
        }

        fun fromCutoff(cutoff: Long): TimeRange = when (cutoff) {
            0L -> ALL
            else -> ALL // 用于动态 cutoff
        }
    }

    fun effectiveCutoff(): Long = when (this) {
        ALL -> 0
        TODAY -> todayCutoff()
        WEEK -> weekCutoff()
        MONTH -> monthCutoff()
    }
}
