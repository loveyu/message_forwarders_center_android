package info.loveyu.mfca.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

object ConfigDownloader {

    private const val TAG = "CONFIG"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun downloadConfig(context: Context, url: String, callback: (Result<String>) -> Unit) {
        LogManager.logDebug(TAG, "开始下载配置: $url")
        Thread {
            val result = try {
                val content = when {
                    url.startsWith("http://") || url.startsWith("https://") -> {
                        LogManager.logDebug(TAG, "检测到HTTP(S)协议，开始网络下载: $url")
                        downloadFromHttp(url)
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

    private fun downloadFromHttp(httpUrl: String): String {
        LogManager.logDebug(TAG, "建立HTTP连接: $httpUrl")
        val url = URL(httpUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        if (connection is HttpsURLConnection) {
            LogManager.logDebug(TAG, "HTTPS连接，使用系统默认证书校验")
        }

        try {
            LogManager.logDebug(TAG, "等待服务器响应...")
            val responseCode = connection.responseCode
            LogManager.logDebug(TAG, "服务器响应码: $responseCode")
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalArgumentException("HTTP error: $responseCode")
            }

            val content = connection.inputStream.bufferedReader().use { it.readText() }
            LogManager.logDebug(TAG, "HTTP下载成功，大小: ${content.length} 字符")
            return content
        } catch (e: SSLException) {
            LogManager.logError(TAG, "SSL异常: ${e.message}")
            throw IllegalArgumentException("SSL证书校验失败: ${e.message}")
        } finally {
            connection.disconnect()
            LogManager.logDebug(TAG, "HTTP连接已关闭")
        }
    }
}
