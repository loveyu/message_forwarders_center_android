package info.loveyu.mfca.util

import android.os.Environment
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

    fun downloadConfig(url: String, callback: (Result<String>) -> Unit) {
        LogManager.log(TAG, "开始下载配置: $url")
        Thread {
            val result = try {
                val content = when {
                    url.startsWith("http://") || url.startsWith("https://") -> {
                        LogManager.log(TAG, "检测到HTTP(S)协议，开始网络下载: $url")
                        downloadFromHttp(url)
                    }
                    url.startsWith("sdcard://") -> {
                        LogManager.log(TAG, "检测到SD卡协议，从SD卡读取: $url")
                        downloadFromSdcard(url)
                    }
                    else -> {
                        LogManager.log(TAG, "不支持的协议: $url，仅支持 http(s):// 和 sdcard://")
                        throw IllegalArgumentException("Unsupported protocol: $url, only http(s):// and sdcard:// are supported")
                    }
                }
                LogManager.log(TAG, "配置下载完成，内容大小: ${content.length} 字符")
                Result.success(content)
            } catch (e: Exception) {
                LogManager.log(TAG, "配置下载失败: ${e.javaClass.simpleName}: ${e.message}")
                Result.failure<String>(e)
            }
            mainHandler.post { callback(result) }
        }.start()
    }

    private fun downloadFromSdcard(sdcardUrl: String): String {
        val relativePath = sdcardUrl.removePrefix("sdcard://")
        val file = File(Environment.getExternalStorageDirectory(), relativePath)
        LogManager.log(TAG, "读取SD卡文件: ${file.absolutePath}")
        if (!file.exists()) {
            LogManager.log(TAG, "文件不存在: ${file.absolutePath}")
            throw IllegalArgumentException("File not found: ${file.absolutePath}")
        }
        if (!file.canRead()) {
            LogManager.log(TAG, "文件不可读: ${file.absolutePath}")
            throw IllegalArgumentException("Cannot read file: ${file.absolutePath}")
        }
        val content = BufferedReader(FileReader(file)).use { it.readText() }
        LogManager.log(TAG, "SD卡文件读取成功，大小: ${content.length} 字符")
        return content
    }

    private fun downloadFromHttp(httpUrl: String): String {
        LogManager.log(TAG, "建立HTTP连接: $httpUrl")
        val url = URL(httpUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        if (connection is HttpsURLConnection) {
            LogManager.log(TAG, "HTTPS连接，使用系统默认证书校验")
        }

        try {
            LogManager.log(TAG, "等待服务器响应...")
            val responseCode = connection.responseCode
            LogManager.log(TAG, "服务器响应码: $responseCode")
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalArgumentException("HTTP error: $responseCode")
            }

            val content = connection.inputStream.bufferedReader().use { it.readText() }
            LogManager.log(TAG, "HTTP下载成功，大小: ${content.length} 字符")
            return content
        } catch (e: SSLException) {
            LogManager.log(TAG, "SSL异常: ${e.message}")
            throw IllegalArgumentException("SSL证书校验失败: ${e.message}")
        } finally {
            connection.disconnect()
            LogManager.log(TAG, "HTTP连接已关闭")
        }
    }
}
