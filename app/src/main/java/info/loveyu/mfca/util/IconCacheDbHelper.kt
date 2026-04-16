package info.loveyu.mfca.util

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 图标缓存数据库帮助类
 * 存储图标 URL 和本地缓存路径的映射关系，有效期 24 小时
 */
class IconCacheDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "icon_cache.db"
        const val DATABASE_VERSION = 1
        const val TABLE_ICON_CACHE = "icon_cache"
        private const val COLUMN_URL = "url"
        private const val COLUMN_LOCAL_PATH = "local_path"
        private const val COLUMN_CACHED_AT = "cached_at"
        const val CACHE_VALIDITY_MS = 24 * 60 * 60 * 1000L // 24 小时
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_ICON_CACHE (
                $COLUMN_URL TEXT PRIMARY KEY,
                $COLUMN_LOCAL_PATH TEXT NOT NULL,
                $COLUMN_CACHED_AT INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cached_at ON $TABLE_ICON_CACHE($COLUMN_CACHED_AT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ICON_CACHE")
        onCreate(db)
    }

    /**
     * 插入或更新图标缓存
     */
    fun putCache(url: String, localPath: String): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_URL, url)
            put(COLUMN_LOCAL_PATH, localPath)
            put(COLUMN_CACHED_AT, System.currentTimeMillis())
        }
        val result = db.insertWithOnConflict(TABLE_ICON_CACHE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return result != -1L
    }

    /**
     * 获取缓存的图标路径，如果不存在或已过期返回 null
     */
    fun getCache(url: String): String? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_ICON_CACHE,
            arrayOf(COLUMN_LOCAL_PATH, COLUMN_CACHED_AT),
            "$COLUMN_URL = ?",
            arrayOf(url),
            null, null, null
        )

        var result: String? = null
        if (cursor.moveToFirst()) {
            val cachedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_CACHED_AT))
            val isValid = System.currentTimeMillis() - cachedAt < CACHE_VALIDITY_MS
            if (isValid) {
                result = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LOCAL_PATH))
            }
        }
        cursor.close()
        return result
    }

    /**
     * 删除指定 URL 的缓存
     */
    fun removeCache(url: String): Boolean {
        val db = writableDatabase
        val deleted = db.delete(TABLE_ICON_CACHE, "$COLUMN_URL = ?", arrayOf(url))
        return deleted > 0
    }

    /**
     * 清理所有过期缓存
     */
    fun cleanupExpired(): Int {
        val cutoff = System.currentTimeMillis() - CACHE_VALIDITY_MS
        val db = writableDatabase
        val deleted = db.delete(TABLE_ICON_CACHE, "$COLUMN_CACHED_AT < ?", arrayOf(cutoff.toString()))
        if (deleted > 0) {
            LogManager.logDebug("ICON_CACHE", "Cleaned up $deleted expired icon cache entries")
        }
        return deleted
    }

    /**
     * 获取缓存条目数量
     */
    fun getCacheCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_ICON_CACHE", null)
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return count
    }
}
