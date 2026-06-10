package info.loveyu.mfca.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

object ConfigDownloader {

    private const val TAG = "CONFIG"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun downloadConfig(
        context: Context,
        url: String,
        insecure: Boolean = false,
        callback: (Result<String>) -> Unit,
    ) {
        LogManager.logDebug(TAG, "开始下载配置: $url")
        Thread {
            val result = try {
                val content = when {
                    url.startsWith("http://") || url.startsWith("https://") -> {
                        LogManager.logDebug(TAG, "检测到HTTP(S)协议，开始网络下载: $url")
                        HttpDownloader.downloadString(
                            url,
                            HttpDownloader.Config(
                                connectTimeoutMs = 10_000L,
                                readTimeoutMs = 10_000L,
                                tag = TAG,
                                insecure = insecure,
                            ),
                        )
                    }
                    else -> {
                        LogManager.logDebug(TAG, "检测到本地路径协议，从本地读取: $url")
                        downloadFromLocalPath(context, url)
                    }
                }
                LogManager.logDebug(TAG, "配置下载完成，内容大小: ${content.length} 字符")
                Result.success(content)
            } catch (e: Exception) {
                LogManager.logError(TAG, "配置下载失败: ${e.javaClass.simpleName}: ${e.message}")
                Result.failure<String>(e)
            }
            mainHandler.post { callback(result) }
        }.start()
    }

    private fun downloadFromLocalPath(context: Context, path: String): String {
        val file = StoragePathResolver.resolveFile(context, path)
        LogManager.logDebug(TAG, "读取本地配置文件: ${file.absolutePath}")
        if (!file.exists()) {
            LogManager.logWarn(TAG, "文件不存在: ${file.absolutePath}")
            throw IllegalArgumentException("File not found: ${file.absolutePath}")
        }
        if (!file.canRead()) {
            LogManager.logWarn(TAG, "文件不可读: ${file.absolutePath}")
            throw IllegalArgumentException("Cannot read file: ${file.absolutePath}")
        }
        val content = BufferedReader(FileReader(file)).use { it.readText() }
        LogManager.logDebug(TAG, "本地配置文件读取成功，大小: ${content.length} 字符")
        return content
    }
}
