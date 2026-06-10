package info.loveyu.mfca.util

import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.cert.CertPathValidatorException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 统一 HTTP 下载工具类
 *
 * 基于 OkHttp，整合项目中所有 HTTP GET 下载逻辑。
 * 支持：可配置超时、代理、SSL 重试、取消、进度回调、重定向跟随。
 *
 * SSL 信任：使用 AndroidCAStore（含系统 + 用户安装的 CA 证书）初始化 TrustManager，
 * 与 network_security_config.xml 中 `<certificates src="user" />` 行为一致。
 */
object HttpDownloader {

    data class Config(
        val connectTimeoutMs: Long = 15_000L,
        val readTimeoutMs: Long = 15_000L,
        val proxy: Proxy? = null,
        val sslRetryCount: Int = 0,
        val sslRetryDelayMs: Long = 1_000L,
        val followRedirects: Boolean = true,
        val tag: String = "HTTP",
        val userAgent: String? = null,
        val insecure: Boolean = false,
    )

    fun interface ProgressCallback {
        fun onProgress(downloadedBytes: Long, totalBytes: Long)
    }

    private val platformTrustManager: X509TrustManager by lazy {
        val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private val permissiveTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<out java.security.cert.X509Certificate>?,
            authType: String?,
        ) {}

        override fun checkServerTrusted(
            chain: Array<out java.security.cert.X509Certificate>?,
            authType: String?,
        ) {}

        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }

    private val sharedClient by lazy {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(platformTrustManager), null)
        }
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .sslSocketFactory(sslContext.socketFactory, platformTrustManager)
            .build()
    }

    private val activeCalls = ConcurrentHashMap<String, okhttp3.Call>()

    // ── Public API: download to String ─────────────────────────────

    fun downloadString(url: String, config: Config = Config()): String {
        return executeWithRetry(config) { client ->
            val request = buildRequest(url, config)
            val call = client.newCall(request)
            activeCalls[config.tag] = call
            try {
                val response = call.execute()
                try {
                    if (!response.isSuccessful) {
                        val body = response.body?.string()?.take(500)
                        throw IOException("HTTP ${response.code}${if (body.isNullOrBlank()) "" else " - $body"}")
                    }
                    return@executeWithRetry response.body?.string()
                        ?: throw IOException("Empty response body")
                } finally {
                    response.close()
                }
            } finally {
                activeCalls.remove(config.tag)
            }
        }
    }

    suspend fun downloadStringSuspend(url: String, config: Config = Config()): String =
        withContext(Dispatchers.IO) {
            var lastException: Exception? = null
            repeat(config.sslRetryCount + 1) { attempt ->
                try {
                    ensureActive()
                    return@withContext downloadString(url, config)
                } catch (e: Exception) {
                    if (e is java.net.SocketException && e.message?.contains("Socket closed") == true) {
                        // Cancelled — don't retry
                        throw e
                    }
                    if (!isSslError(e) || attempt >= config.sslRetryCount) throw e
                    lastException = e
                    LogManager.logWarn(
                        config.tag,
                        "SSL 错误 (第${attempt + 1}次)，重试中: ${e.message}",
                    )
                    kotlinx.coroutines.delay(config.sslRetryDelayMs * (attempt + 1))
                }
            }
            throw lastException!!
        }

    // ── Public API: download to ByteArray ──────────────────────────

    fun downloadBytes(url: String, config: Config = Config(), maxSize: Long = -1L): ByteArray {
        return executeWithRetry(config) { client ->
            val request = buildRequest(url, config)
            val call = client.newCall(request)
            activeCalls[config.tag] = call
            try {
                val response = call.execute()
                try {
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    val contentLength = response.body?.contentLength() ?: -1L
                    if (maxSize > 0 && contentLength > maxSize) {
                        throw IOException("Content too large: $contentLength bytes (max $maxSize)")
                    }
                    val body = response.body ?: throw IOException("Empty response body")
                    val baos = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var totalRead = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            totalRead += n
                            if (maxSize > 0 && totalRead > maxSize) {
                                throw IOException("Content too large: exceeded $maxSize bytes")
                            }
                            baos.write(buf, 0, n)
                        }
                    }
                    return@executeWithRetry baos.toByteArray()
                } finally {
                    response.close()
                }
            } finally {
                activeCalls.remove(config.tag)
            }
        }
    }

    // ── Public API: download to File ───────────────────────────────

    fun downloadToFile(
        url: String,
        destFile: File,
        config: Config = Config(),
        progressCallback: ProgressCallback? = null,
    ): File {
        return executeWithRetry(config) { client ->
            val request = buildRequest(url, config)
            val call = client.newCall(request)
            activeCalls[config.tag] = call
            try {
                val response = call.execute()
                try {
                    if (!response.isSuccessful) {
                        val body = response.body?.string()?.take(500)
                        throw IOException("HTTP ${response.code}${if (body.isNullOrBlank()) "" else " - $body"}")
                    }
                    val totalSize = response.body?.contentLength() ?: -1L
                    val body = response.body ?: throw IOException("Empty response body")
                    destFile.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        destFile.outputStream().buffered().use { output ->
                            val buf = ByteArray(8192)
                            var downloaded = 0L
                            var lastLogTime = System.currentTimeMillis()
                            while (true) {
                                val n = input.read(buf)
                                if (n == -1) break
                                output.write(buf, 0, n)
                                downloaded += n
                                if (progressCallback != null) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastLogTime >= 20_000) {
                                        progressCallback.onProgress(downloaded, totalSize)
                                        lastLogTime = now
                                    }
                                }
                            }
                            if (progressCallback != null) {
                                progressCallback.onProgress(downloaded, totalSize)
                            }
                        }
                    }
                    return@executeWithRetry destFile
                } finally {
                    response.close()
                }
            } finally {
                activeCalls.remove(config.tag)
            }
        }
    }

    suspend fun downloadToFileSuspend(
        url: String,
        destFile: File,
        config: Config = Config(),
        progressCallback: ProgressCallback? = null,
    ): File = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        repeat(config.sslRetryCount + 1) { attempt ->
            try {
                ensureActive()
                return@withContext downloadToFile(url, destFile, config, progressCallback)
            } catch (e: Exception) {
                if (e is java.net.SocketException && e.message?.contains("Socket closed") == true) {
                    throw e
                }
                if (!isSslError(e) || attempt >= config.sslRetryCount) throw e
                lastException = e
                LogManager.logWarn(
                    config.tag,
                    "SSL 错误 (第${attempt + 1}次)，重试中: ${e.message}",
                )
                kotlinx.coroutines.delay(config.sslRetryDelayMs * (attempt + 1))
            }
        }
        throw lastException!!
    }

    // ── Public API: open raw Response ───────────────────────────────

    fun openResponse(url: String, config: Config = Config()): okhttp3.Response {
        val client = buildClient(config)
        val request = buildRequest(url, config)
        val call = client.newCall(request)
        activeCalls[config.tag] = call
        return call.execute()
    }

    // ── Utility: proxy parsing ──────────────────────────────────────

    fun parseProxy(address: String?): Proxy? {
        if (address.isNullOrBlank()) return null
        val trimmed = address.trim()
        return when {
            trimmed.startsWith("socks5://", ignoreCase = true) ||
                trimmed.startsWith("socks4://", ignoreCase = true) -> {
                val parts = trimmed.substringAfter("://").split(":")
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(parts[0], parts.getOrElse(1) { "1080" }.toInt()))
            }
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> {
                val parts = trimmed.substringAfter("://").split(":")
                Proxy(Proxy.Type.HTTP, InetSocketAddress(parts[0], parts.getOrElse(1) { "8080" }.toInt()))
            }
            else -> {
                val parts = trimmed.split(":")
                Proxy(Proxy.Type.HTTP, InetSocketAddress(parts[0], parts.getOrElse(1) { "8080" }.toInt()))
            }
        }
    }

    // ── Utility: SSL error detection ────────────────────────────────

    fun isSslError(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is SSLException) return true
            if (cause is CertPathValidatorException) return true
            cause = cause.cause
        }
        return false
    }

    // ── Cancellation ────────────────────────────────────────────────

    fun cancel(id: String): Boolean {
        val call = activeCalls.remove(id) ?: return false
        call.cancel()
        return true
    }

    // ── Internal ───────────────────────────────────────────────────

    private fun buildClient(config: Config): OkHttpClient {
        val baseBuilder = if (config.insecure) {
            LogManager.logWarn(config.tag, "SSL 证书校验已禁用 (insecure 模式)")
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(permissiveTrustManager), null)
            }
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, permissiveTrustManager)
                .hostnameVerifier { _, _ -> true }
        } else {
            sharedClient.newBuilder()
        }
        return baseBuilder.apply {
            connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            followRedirects(config.followRedirects)
            followSslRedirects(config.followRedirects)
            config.proxy?.let { proxy(it) }
        }.build()
    }

    private fun buildRequest(url: String, config: Config): Request {
        return Request.Builder().url(url).apply {
            config.userAgent?.let { header("User-Agent", it) }
        }.build()
    }

    private inline fun <T> executeWithRetry(config: Config, block: (OkHttpClient) -> T): T {
        var lastException: Exception? = null
        val client = buildClient(config)
        repeat(config.sslRetryCount + 1) { attempt ->
            try {
                return block(client)
            } catch (e: Exception) {
                if (e is java.net.SocketException && e.message?.contains("Socket closed") == true) {
                    // Cancelled — don't retry
                    throw e
                }
                if (!isSslError(e) || attempt >= config.sslRetryCount) throw e
                lastException = e
                LogManager.logWarn(
                    config.tag,
                    "SSL 错误 (第${attempt + 1}次)，重试中: ${e.message}",
                )
                Thread.sleep(config.sslRetryDelayMs * (attempt + 1))
            }
        }
        throw lastException!!
    }
}