package info.loveyu.mfca.output

import info.loveyu.mfca.config.HttpOutputConfig
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP 输出（OkHttp 连接池复用）
 */
class HttpOutput(
    override val name: String,
    private val config: HttpOutputConfig
) : Output {

    override val type: OutputType = OutputType.http
    override val formatSteps get() = config.effectiveFormatSteps

    @Volatile
    private var available = true

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 共享 OkHttpClient，复用连接池
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(config.timeout.millis, TimeUnit.MILLISECONDS)
            .readTimeout(config.timeout.millis, TimeUnit.MILLISECONDS)
            .writeTimeout(config.timeout.millis, TimeUnit.MILLISECONDS)
            .build()
    }

    override fun send(item: QueueItem, callback: ((Boolean) -> Unit)?) {
        scope.launch {
            var attempt = 0
            val maxAttempts = config.retry?.maxAttempts ?: 1

            if (LogManager.isDebugEnabled()) {
                LogManager.logDebug("HTTP", "send() - name=$name, ${config.method} ${config.url}, dataLen=${item.data.size}, maxAttempts=$maxAttempts")
            }

            while (attempt < maxAttempts) {
                try {
                    val result = doSend(item)
                    if (result is OutputResult.Success) {
                        LogManager.logDebug("HTTP", "HTTP output $name succeeded (${result.responseCode})")
                        callback?.invoke(true)
                        return@launch
                    } else {
                        LogManager.logWarn("HTTP", "HTTP output $name failed: ${(result as? OutputResult.Failure)?.error}")
                    }
                } catch (e: Exception) {
                    LogManager.logError("HTTP", "HTTP output $name error: ${e.message}")
                }

                attempt++
                if (attempt < maxAttempts) {
                    val interval = config.retry?.interval?.millis ?: 1000
                    if (LogManager.isDebugEnabled()) {
                        LogManager.logDebug("HTTP", "[$name] Retry $attempt/$maxAttempts after ${interval}ms")
                    }
                    delay(interval)
                }
            }

            LogManager.logError("HTTP", "HTTP output $name exhausted all $maxAttempts attempts")
            callback?.invoke(false)
        }
    }

    private fun doSend(item: QueueItem): OutputResult {
        return try {
            val prepared = prepareRequest(item)
            val body =
                if (allowsRequestBody(prepared.method)) {
                    prepared.body.toRequestBody(prepared.contentType.toMediaType())
                } else {
                    null
                }
            val request = Request.Builder()
                .url(config.url)
                .apply {
                    prepared.headers.forEach { (key, value) ->
                        header(key, value)
                    }
                }
                .method(prepared.method, body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseCode = response.code
                if (responseCode in 200..299) {
                    OutputResult.Success(responseCode)
                } else {
                    OutputResult.Failure("HTTP $responseCode")
                }
            }
        } catch (e: Exception) {
            available = false
            OutputResult.Failure(e.message ?: "Unknown error", e)
        }
    }

    internal fun prepareRequest(item: QueueItem): PreparedRequest {
        val method = config.method.uppercase()
        val normalizedHeaders = normalizeHeaders(item.headers)
        val contentType =
            removeHeaderIgnoreCase(normalizedHeaders, "content-type")
                ?: "application/octet-stream"
        removeHeaderIgnoreCase(normalizedHeaders, "content-length")
        return PreparedRequest(
            method = method,
            body = item.data,
            contentType = contentType,
            headers = normalizedHeaders
        )
    }

    private fun allowsRequestBody(method: String): Boolean = method != "GET" && method != "HEAD"

    private fun normalizeHeaders(headers: Map<String, String>): LinkedHashMap<String, String> {
        val normalized = linkedMapOf<String, String>()
        val originalKeys = mutableMapOf<String, String>()
        headers.forEach { (key, value) ->
            val normalizedKey = key.lowercase()
            val originalKey = originalKeys.remove(normalizedKey)
            if (originalKey != null) {
                normalized.remove(originalKey)
            }
            originalKeys[normalizedKey] = key
            normalized[key] = value
        }
        return normalized
    }

    private fun removeHeaderIgnoreCase(headers: MutableMap<String, String>, name: String): String? {
        val matchedKey = headers.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
        return headers.remove(matchedKey)
    }

    override fun isAvailable(): Boolean = available

    fun shutdown() {
        scope.cancel()
    }
}

internal data class PreparedRequest(
    val method: String,
    val body: ByteArray,
    val contentType: String,
    val headers: Map<String, String>
)
