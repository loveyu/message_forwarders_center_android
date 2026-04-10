package info.loveyu.mfca.util

import android.content.Context
import android.os.Environment
import info.loveyu.mfca.config.TlsConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * TLS 证书解析器
 *
 * 支持协议:
 * - file:///path/to/cert.pem     - 直接文件路径
 * - sdcard://path/to/cert.pem   - 外部存储卡路径
 * - https://example.com/cert.pem - 网络下载（缓存到应用外部目录）
 * - http://example.com/cert.pem  - 网络下载（缓存到应用外部目录）
 *
 * 网络证书下载后存储在应用外部扩展目录，以 hash 值命名，仅下载一次
 */
object CertResolver {

    private const val TAG = "CERT"
    private const val CERT_DIR = "certs"
    private val downloadedCerts = mutableMapOf<String, String>() // url -> localPath

    /**
     * 解析证书路径
     * @param certPath 证书路径，支持 file://, sdcard://, https://, http://
     * @param context Android Context
     * @return 本地文件路径
     */
    fun resolveCertPath(certPath: String?, context: Context): String? {
        if (certPath == null) return null

        return when {
            certPath.startsWith("file://") -> {
                // 直接文件路径
                val filePath = certPath.removePrefix("file://")
                val file = File(filePath)
                if (file.exists() && file.canRead()) filePath else null
            }
            certPath.startsWith("sdcard://") -> {
                // 外部存储卡路径
                val relativePath = certPath.removePrefix("sdcard://")
                val sdcardDir = Environment.getExternalStorageDirectory()
                val file = File(sdcardDir, relativePath)
                if (file.exists() && file.canRead()) file.absolutePath else null
            }
            certPath.startsWith("https://") || certPath.startsWith("http://") -> {
                // 网络下载（带缓存）
                downloadAndCacheCert(certPath, context)
            }
            else -> {
                // 假设是绝对路径
                val file = File(certPath)
                if (file.exists() && file.canRead()) certPath else null
            }
        }
    }

    /**
     * 解析完整的 TLS 配置
     * @param tls TLS 配置
     * @param context Android Context
     * @return 解析后的本地路径 TLS 配置，如果证书无法解析则返回 null
     */
    fun resolveTlsConfig(tls: TlsConfig?, context: Context): TlsConfig? {
        if (tls == null) return null

        val resolvedCa = resolveCertPath(tls.ca, context)
        val resolvedCert = resolveCertPath(tls.cert, context)
        val resolvedKey = resolveCertPath(tls.key, context)

        if (resolvedCa == null && resolvedCert == null && resolvedKey == null) {
            return null
        }

        return TlsConfig(
            ca = resolvedCa,
            cert = resolvedCert,
            key = resolvedKey
        )
    }

    /**
     * 下载并缓存证书
     * 缓存路径: 应用外部扩展目录/certs/<hash>
     * 仅在 hash 不匹配时重新下载
     */
    private fun downloadAndCacheCert(url: String, context: Context): String? {
        // 检查内存缓存
        downloadedCerts[url]?.let { cachedPath ->
            val cachedFile = File(cachedPath)
            if (cachedFile.exists()) {
                LogManager.appendLog(TAG, "使用缓存证书: $cachedPath")
                return cachedPath
            }
        }

        // 创建证书目录
        val certDir = getCertDir(context)
        if (certDir == null) {
            LogManager.appendLog(TAG, "无法创建证书目录")
            return null
        }

        return try {
            // 计算 URL hash 作为文件名
            val urlHash = hashString(url)
            val certFile = File(certDir, urlHash)

            // 检查文件是否已存在
            if (certFile.exists()) {
                LogManager.appendLog(TAG, "证书已存在（hash匹配）: ${certFile.absolutePath}")
                downloadedCerts[url] = certFile.absolutePath
                return certFile.absolutePath
            }

            // 下载证书
            LogManager.appendLog(TAG, "开始下载证书: $url")
            val certContent = downloadFromHttp(url)

            // 写入文件
            FileOutputStream(certFile).use { fos ->
                fos.write(certContent.toByteArray())
            }

            LogManager.appendLog(TAG, "证书下载完成: ${certFile.absolutePath}")
            downloadedCerts[url] = certFile.absolutePath
            certFile.absolutePath
        } catch (e: Exception) {
            LogManager.appendLog(TAG, "证书下载失败: ${e.message}")
            null
        }
    }

    /**
     * 从网络下载内容
     */
    private fun downloadFromHttp(httpUrl: String): String {
        val url = URL(httpUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalArgumentException("HTTP error: $responseCode")
            }

            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 获取证书存储目录
     */
    private fun getCertDir(context: Context): File? {
        val externalDir = context.getExternalFilesDir(null) ?: return null
        val certDir = File(externalDir, CERT_DIR)
        if (!certDir.exists()) {
            certDir.mkdirs()
        }
        return if (certDir.exists()) certDir else null
    }

    /**
     * 计算字符串的 MD5 hash
     */
    private fun hashString(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 清除所有缓存的证书下载路径
     */
    fun clearCache() {
        downloadedCerts.clear()
    }
}
