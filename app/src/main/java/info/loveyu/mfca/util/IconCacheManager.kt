package info.loveyu.mfca.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 图标缓存管理器
 * 负责下载、缓存和清理图标
 */
class IconCacheManager(private val context: Context) {

    private val dbHelper = IconCacheDbHelper(context)
    private val cacheDir: File by lazy {
        File(context.cacheDir, "icons").apply { mkdirs() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 10000
        private const val MAX_ICON_SIZE = 100 * 1024 // 100KB
    }

    /**
     * 获取图标位图，如果缓存存在且有效则直接返回，否则下载并缓存
     * @param iconUrl 图标 URL，可以是网络 URL 或本地路径
     * @param fixedIconId 固定图标 ID（如配置中指定的图标资源）
     * @return 图标位图或 null
     */
    suspend fun getIcon(iconUrl: String?, fixedIconId: String?): Bitmap? = withContext(Dispatchers.IO) {
        // 如果配置了固定图标，优先使用固定图标
        if (fixedIconId != null) {
            getFixedIcon(fixedIconId)?.let { return@withContext it }
        }

        // 如果没有图标 URL，返回 null
        if (iconUrl.isNullOrBlank()) {
            return@withContext null
        }

        // 检查是否是 URL
        if (!iconUrl.startsWith("http://") && !iconUrl.startsWith("https://")) {
            // 假设是本地路径，尝试加载
            return@withContext loadLocalIcon(iconUrl)
        }

        // 检查缓存
        dbHelper.getCache(iconUrl)?.let { cachedPath ->
            val file = File(cachedPath)
            if (file.exists()) {
                return@withContext loadBitmapFromFile(file)
            }
        }

        // 下载图标
        downloadAndCacheIcon(iconUrl)
    }

    /**
     * 获取固定图标（通过图标 ID）
     */
    private fun getFixedIcon(iconId: String): Bitmap? {
        return try {
            val resourceId = when (iconId.lowercase()) {
                "clipboard" -> android.R.drawable.ic_menu_edit
                "share" -> android.R.drawable.ic_menu_share
                "send" -> android.R.drawable.ic_menu_send
                "info" -> android.R.drawable.ic_menu_info_details
                "warning" -> android.R.drawable.ic_dialog_alert
                "error" -> android.R.drawable.ic_delete
                "star" -> android.R.drawable.btn_star_big_on
                "email" -> android.R.drawable.ic_dialog_email
                "call" -> android.R.drawable.ic_menu_call
                else -> null
            }
            resourceId?.let { ContextCompat.getDrawable(context, it)?.toBitmap() }
        } catch (e: Exception) {
            LogManager.log("ICON_CACHE", "Failed to load fixed icon $iconId: ${e.message}")
            null
        }
    }

    private fun android.graphics.drawable.Drawable.toBitmap(): Bitmap {
        val width = if (intrinsicWidth > 0) intrinsicWidth else 48
        val height = if (intrinsicHeight > 0) intrinsicHeight else 48
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }

    /**
     * 从本地路径加载图标
     */
    private fun loadLocalIcon(path: String): Bitmap? {
        return try {
            val file = if (path.startsWith("file://")) {
                File(path.removePrefix("file://"))
            } else {
                File(path)
            }
            if (file.exists()) {
                loadBitmapFromFile(file)
            } else {
                null
            }
        } catch (e: Exception) {
            LogManager.log("ICON_CACHE", "Failed to load local icon $path: ${e.message}")
            null
        }
    }

    /**
     * 从文件加载位图
     */
    private fun loadBitmapFromFile(file: File): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 2 // 缩小图片以节省内存
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Exception) {
            LogManager.log("ICON_CACHE", "Failed to decode bitmap from ${file.absolutePath}: ${e.message}")
            null
        }
    }

    /**
     * 下载并缓存图标
     */
    private suspend fun downloadAndCacheIcon(url: String): Bitmap? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val targetUrl = URL(url)
            connection = targetUrl.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                LogManager.log("ICON_CACHE", "HTTP error $responseCode for $url")
                return@withContext null
            }

            val contentLength = connection.contentLength
            if (contentLength > MAX_ICON_SIZE) {
                LogManager.log("ICON_CACHE", "Icon too large: $contentLength bytes for $url")
                return@withContext null
            }

            val inputStream = connection.inputStream
            val bytes = inputStream.readBytes()

            // 生成缓存文件名
            val fileName = "${url.hashCode()}.icon"
            val cacheFile = File(cacheDir, fileName)

            // 写入缓存文件
            FileOutputStream(cacheFile).use { fos ->
                fos.write(bytes)
            }

            // 保存到数据库
            dbHelper.putCache(url, cacheFile.absolutePath)

            // 加载并返回位图
            loadBitmapFromFile(cacheFile)
        } catch (e: Exception) {
            LogManager.log("ICON_CACHE", "Failed to download icon $url: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 清理过期缓存
     */
    fun cleanupExpired() {
        val deleted = dbHelper.cleanupExpired()
        if (deleted > 0) {
            LogManager.log("ICON_CACHE", "Cleaned up $deleted expired icon cache entries")
        }
    }

    /**
     * 清理所有缓存
     */
    fun clearAll() {
        // 删除缓存目录中的文件
        cacheDir.listFiles()?.forEach { it.delete() }
        // 清理数据库记录
        val db = dbHelper.writableDatabase
        db.delete(IconCacheDbHelper.TABLE_ICON_CACHE, null, null)
        LogManager.log("ICON_CACHE", "Cleared all icon cache")
    }

    /**
     * 获取缓存统计
     */
    fun getCacheStats(): Pair<Int, Long> {
        val count = dbHelper.getCacheCount()
        val size = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        return count to size
    }
}
