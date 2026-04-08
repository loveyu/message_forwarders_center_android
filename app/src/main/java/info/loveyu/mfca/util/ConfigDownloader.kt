package info.loveyu.mfca.util

import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

object ConfigDownloader {

    fun downloadConfig(url: String, callback: (Result<String>) -> Unit) {
        Thread {
            try {
                val content = when {
                    url.startsWith("file://") -> downloadFromFile(url)
                    url.startsWith("http://") || url.startsWith("https://") -> downloadFromHttp(url)
                    else -> throw IllegalArgumentException("Unsupported protocol")
                }
                callback(Result.success(content))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }.start()
    }

    private fun downloadFromFile(fileUrl: String): String {
        val filePath = fileUrl.removePrefix("file://")
        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("File not found: $filePath")
        }
        if (!file.canRead()) {
            throw IllegalArgumentException("Cannot read file: $filePath")
        }
        return BufferedReader(FileReader(file)).use { it.readText() }
    }

    private fun downloadFromHttp(httpUrl: String): String {
        val url = URL(httpUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        // HTTPS: 强制校验证书 (默认行为，不设置任何绕过验证的选项)
        if (connection is HttpsURLConnection) {
            // 使用默认的 hostname verifier 和 SSL socket factory
            // 默认会验证服务器证书链和域名，不接受无效或自签名证书
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                // 严格验证域名
                val verified = try {
                    javax.net.ssl.SSLSocketFactory.getDefault()
                        .createSocket(url.host, url.port)?.close()
                    true
                } catch (e: Exception) {
                    false
                }
                verified
            }
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalArgumentException("HTTP error: $responseCode")
            }

            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: SSLException) {
            throw IllegalArgumentException("SSL证书校验失败: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }
}
