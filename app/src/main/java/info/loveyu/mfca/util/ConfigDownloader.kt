package info.loveyu.mfca.util

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
        LogManager.appendLog(TAG, "开始下载配置: $url")
        Thread {
            val result = try {
                val content = when {
                    url.startsWith("file://") -> {
                        LogManager.appendLog(TAG, "检测到本地文件协议，从文件读取: $url")
                        downloadFromFile(url)
                    }
                    url.startsWith("http://") || url.startsWith("https://") -> {
                        LogManager.appendLog(TAG, "检测到HTTP(S)协议，开始网络下载: $url")
                        downloadFromHttp(url)
                    }
                    else -> {
                        LogManager.appendLog(TAG, "不支持的协议: $url")
                        throw IllegalArgumentException("Unsupported protocol: $url")
                    }
                }
                LogManager.appendLog(TAG, "配置下载完成，内容大小: ${content.length} 字符")
                Result.success(content)
            } catch (e: Exception) {
                LogManager.appendLog(TAG, "配置下载失败: ${e.javaClass.simpleName}: ${e.message}")
                Result.failure<String>(e)
            }
            mainHandler.post { callback(result) }
        }.start()
    }

    private fun downloadFromFile(fileUrl: String): String {
        val filePath = fileUrl.removePrefix("file://")
        LogManager.appendLog(TAG, "读取本地文件: $filePath")
        val file = File(filePath)
        if (!file.exists()) {
            LogManager.appendLog(TAG, "文件不存在: $filePath")
            throw IllegalArgumentException("File not found: $filePath")
        }
        if (!file.canRead()) {
            LogManager.appendLog(TAG, "文件不可读: $filePath")
            throw IllegalArgumentException("Cannot read file: $filePath")
        }
        val content = BufferedReader(FileReader(file)).use { it.readText() }
        LogManager.appendLog(TAG, "本地文件读取成功，大小: ${content.length} 字符")
        return content
    }

    private fun downloadFromHttp(httpUrl: String): String {
        LogManager.appendLog(TAG, "建立HTTP连接: $httpUrl")
        val url = URL(httpUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        if (connection is HttpsURLConnection) {
            LogManager.appendLog(TAG, "HTTPS连接，使用系统默认证书校验")
        }

        try {
            LogManager.appendLog(TAG, "等待服务器响应...")
            val responseCode = connection.responseCode
            LogManager.appendLog(TAG, "服务器响应码: $responseCode")
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalArgumentException("HTTP error: $responseCode")
            }

            val content = connection.inputStream.bufferedReader().use { it.readText() }
            LogManager.appendLog(TAG, "HTTP下载成功，大小: ${content.length} 字符")
            return content
        } catch (e: SSLException) {
            LogManager.appendLog(TAG, "SSL异常: ${e.message}")
            throw IllegalArgumentException("SSL证书校验失败: ${e.message}")
        } finally {
            connection.disconnect()
            LogManager.appendLog(TAG, "HTTP连接已关闭")
        }
    }
}
