package info.loveyu.mfca.util

import android.content.Context
import android.os.Environment
import android.util.Base64
import info.loveyu.mfca.config.TlsConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS 证书解析器
 *
 * 支持协议:
 * - file:///path/to/cert.pem     - 文件系统绝对路径
 * - sdcard://path/to/cert.pem    - 外部存储卡路径
 * - data://path/to/cert.pem      - 应用私有数据目录
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
     * @param certPath 证书路径，支持 file://, sdcard://, data://, https://, http://
     * @param context Android Context
     * @return 本地文件路径
     */
    fun resolveCertPath(certPath: String?, context: Context): String? {
        if (certPath == null) return null

        return when {
            certPath.startsWith("file://") -> {
                // 文件系统绝对路径
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
            certPath.startsWith("data://") -> {
                // 应用私有数据目录
                val relativePath = certPath.removePrefix("data://")
                val externalDir = context.getExternalFilesDir(null)
                val file = if (externalDir != null) {
                    File(externalDir, relativePath)
                } else {
                    File(context.filesDir, relativePath)
                }
                if (file.exists() && file.canRead()) file.absolutePath else null
            }
            certPath.startsWith("https://") || certPath.startsWith("http://") -> {
                // 网络下载（带缓存）
                downloadAndCacheCert(certPath, context)
            }
            else -> {
                LogManager.logWarn(TAG, "不支持的证书路径协议: $certPath，需使用 file://, sdcard://, data:// 或 http(s)://")
                null
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
                LogManager.logDebug(TAG, "使用缓存证书: $cachedPath")
                return cachedPath
            }
        }

        // 创建证书目录
        val certDir = getCertDir(context)
        if (certDir == null) {
            LogManager.logError(TAG, "无法创建证书目录")
            return null
        }

        return try {
            // 计算 URL hash 作为文件名
            val urlHash = hashString(url)
            val certFile = File(certDir, urlHash)

            // 检查文件是否已存在
            if (certFile.exists()) {
                LogManager.logDebug(TAG, "证书已存在（hash匹配）: ${certFile.absolutePath}")
                downloadedCerts[url] = certFile.absolutePath
                return certFile.absolutePath
            }

            // 下载证书
            LogManager.logDebug(TAG, "开始下载证书: $url")
            val certContent = downloadFromHttp(url)

            // 写入文件
            FileOutputStream(certFile).use { fos ->
                fos.write(certContent.toByteArray())
            }

            LogManager.logDebug(TAG, "证书下载完成: ${certFile.absolutePath}")
            downloadedCerts[url] = certFile.absolutePath
            certFile.absolutePath
        } catch (e: Exception) {
            LogManager.logError(TAG, "证书下载失败: ${e.message}")
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
     * 创建自定义 SSL 配置（含自定义 CA 证书）
     *
     * 当配置了自定义 CA 时，创建基于该 CA 的 SSLContext；
     * 未配置自定义 CA 时返回 null，表示应使用系统默认信任库。
     *
     * @param tls TLS 配置，可为 null
     * @param context Android Context
     * @param tag 日志标签
     * @param insecure 跳过证书校验（仅开发调试）
     * @param onPeerCerts 服务端证书链回调，用于 TLS 信息采集
     * @return SslResult 或 null（无自定义 CA 且非 insecure，使用系统默认）
     */
    fun createSslConfig(
        tls: TlsConfig?,
        context: Context,
        tag: String,
        insecure: Boolean = false,
        onPeerCerts: ((List<X509Certificate>) -> Unit)? = null
    ): SslResult? {
        try {
            // Insecure mode: trust all certificates (development only)
            if (insecure) {
                val permissiveTm = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                        chain?.let { onPeerCerts?.invoke(it.toList()) }
                    }
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
                val sslContext = SSLContext.getInstance("TLSv1.2")
                sslContext.init(null, arrayOf(permissiveTm), null)
                LogManager.logWarn(tag, "TLS certificate verification DISABLED (insecure mode)")
                return SslResult(sslContext.socketFactory, permissiveTm as X509TrustManager)
            }

            val resolvedTls = resolveTlsConfig(tls, context)

            val certFactory = CertificateFactory.getInstance("X.509")
            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)

            var caLoaded = false
            resolvedTls?.ca?.let { caPath ->
                val caFile = File(caPath)
                if (caFile.exists()) {
                    val caCert = certFactory.generateCertificate(caFile.inputStream()) as X509Certificate
                    keyStore.setCertificateEntry("ca", caCert)
                    caLoaded = true
                    LogManager.logDebug(tag, "Loaded CA cert from: $caPath")
                } else {
                    LogManager.logWarn(tag, "CA cert file not found: $caPath")
                }
            }

            if (!caLoaded) {
                return null
            }

            trustManagerFactory.init(keyStore)

            val wrappedTms = trustManagerFactory.trustManagers.map { tm ->
                if (tm is X509TrustManager) {
                    object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                            tm.checkClientTrusted(chain, authType)
                        }
                        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                            chain?.let { certs ->
                                onPeerCerts?.invoke(certs.toList())
                            }
                            tm.checkServerTrusted(chain, authType)
                        }
                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = tm.acceptedIssuers
                    }
                } else tm
            }.toTypedArray()

            val x509Tm = wrappedTms.firstOrNull { it is X509TrustManager } as? X509TrustManager
                ?: return null

            // Load client certificate and key for mTLS
            var keyManagers: Array<javax.net.ssl.KeyManager>? = null
            if (resolvedTls?.cert != null && resolvedTls.key != null) {
                try {
                    val certFile = File(resolvedTls.cert!!)
                    val keyFile = File(resolvedTls.key!!)
                    if (certFile.exists() && keyFile.exists()) {
                        val clientCert = certFactory.generateCertificate(certFile.inputStream()) as X509Certificate
                        val keyBytes = extractPemBody(keyFile.readText(), "-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----")
                            ?: extractPemBody(keyFile.readText(), "-----BEGIN RSA PRIVATE KEY-----", "-----END RSA PRIVATE KEY-----")
                        if (keyBytes != null) {
                            val keySpec = PKCS8EncodedKeySpec(keyBytes)
                            val keyFactory = KeyFactory.getInstance(clientCert.publicKey.algorithm)
                            val privateKey = keyFactory.generatePrivate(keySpec)
                            val clientKeyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                            clientKeyStore.load(null, null)
                            clientKeyStore.setKeyEntry("client", privateKey, charArrayOf(), arrayOf(clientCert))
                            val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
                            kmf.init(clientKeyStore, charArrayOf())
                            keyManagers = kmf.keyManagers
                            LogManager.logDebug(tag, "Loaded client cert for mTLS from: ${resolvedTls.cert}")
                        } else {
                            LogManager.logWarn(tag, "Private key format not supported (requires PKCS#8 or PKCS#1 RSA PEM)")
                        }
                    } else {
                        if (!certFile.exists()) LogManager.logWarn(tag, "Client cert file not found: ${resolvedTls.cert}")
                        if (!keyFile.exists()) LogManager.logWarn(tag, "Client key file not found: ${resolvedTls.key}")
                    }
                } catch (e: Exception) {
                    LogManager.logError(tag, "Failed to load client cert/key for mTLS: ${e.message}")
                }
            }

            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(keyManagers, wrappedTms, null)

            return SslResult(sslContext.socketFactory, x509Tm)
        } catch (e: Exception) {
            LogManager.logError(tag, "SSL config error: ${e.message}")
            return null
        }
    }

    /**
     * 从 PEM 文件内容提取 Base64 编码的证书/密钥数据块
     */
    private fun extractPemBody(pemContent: String, beginMarker: String, endMarker: String): ByteArray? {
        val start = pemContent.indexOf(beginMarker)
        val end = pemContent.indexOf(endMarker)
        if (start == -1 || end == -1) return null
        val base64 = pemContent.substring(start + beginMarker.length, end)
            .replace("\\s".toRegex(), "")
        return Base64.decode(base64, Base64.DEFAULT)
    }

    data class SslResult(
        val socketFactory: javax.net.ssl.SSLSocketFactory,
        val trustManager: X509TrustManager
    )

    /**
     */
    fun clearCache() {
        downloadedCerts.clear()
    }
}
