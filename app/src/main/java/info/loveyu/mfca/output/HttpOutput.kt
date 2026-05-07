package info.loveyu.mfca.output

import info.loveyu.mfca.config.HttpOutputConfig
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.queue.QueueManager
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
    override val queueRef get() = config.queue

    @Volatile
    private var available = true

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 共享 OkHttpClient，复用连接池
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(config.timeout.millis, TimeUnit.MILLISECONDS)
            .readTimeout(config.timeout.millis, TimeUnit.MILLISECONDS)
            .writeTimeout(config.timeout.millis, TimeUnit.MILLISECONDS)
            .addNetworkInterceptor(OkHttpLoggingInterceptor(name))
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
            handleOnFailureQueue(item)
            callback?.invoke(false)
        }
    }

    private fun handleOnFailureQueue(item: QueueItem) {
        val queueRef = config.onFailureQueue ?: return

        val failItem = item.copy(
            metadata = item.metadata + mapOf(
                "outputName" to name,
                "failedAt" to System.currentTimeMillis().toString()
            ),
            retryCount = 0,
            nextAttemptAt = System.currentTimeMillis() + queueRef.delay.millis
        )

        val queue = QueueManager.getQueue(queueRef.name)
        val queued = queue?.enqueue(failItem) ?: run {
            LogManager.logWarn("HTTP", "[$name] onFailureQueue not found: ${queueRef.name}")
            false
        }

        if (queued) {
            LogManager.logDebug("HTTP", "[$name] Queued failed item to onFailureQueue=${queueRef.name}")
        } else {
            LogManager.logWarn("HTTP", "[$name] Failed to enqueue item to onFailureQueue: ${queueRef.name}")
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

        // Collect header keys explicitly set by this output's format steps
        val configuredHeaderKeys = buildSet<String> {
            config.effectiveFormatSteps?.forEach { step ->
                val target = step.target ?: return@forEach
                if (target.startsWith("\$header.", ignoreCase = true)) {
                    add(target.removePrefix("\$header.").lowercase())
                }
            }
        }

        // Only forward content-type from the incoming message, plus headers explicitly
        // defined in the output config. All other input headers (remote-addr, host,
        // X-Matched-Path, etc.) are internal routing metadata and must not be forwarded.
        val filteredHeaders = linkedMapOf<String, String>()
        item.headers.forEach { (k, v) ->
            val lower = k.lowercase()
            if (lower == "content-type" || lower in configuredHeaderKeys) {
                filteredHeaders[k] = v
            }
        }

        val contentType =
            removeHeaderIgnoreCase(filteredHeaders, "content-type") ?: "application/octet-stream"
        removeHeaderIgnoreCase(filteredHeaders, "content-length")
        return PreparedRequest(
            method = method,
            body = item.data,
            contentType = contentType,
            headers = filteredHeaders
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

/**
 * OkHttp 网络拦截器：在 Debug 模式下打印完整请求和响应信息。
 * 使用 addNetworkInterceptor 可以捕获 OkHttp 最终发出的原始 header，
 * 包括自动添加的 Host、User-Agent、Content-Length、Accept-Encoding 等默认值。
 */
internal class OkHttpLoggingInterceptor(private val outputName: String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!LogManager.isDebugEnabled()) return chain.proceed(chain.request())

        val request = chain.request()

        // Log request (body is read non-destructively via peek)
        val reqBodyStr = request.body?.let { body ->
            val buffer = okio.Buffer()
            body.writeTo(buffer)
            buffer.readUtf8().take(500).let { s ->
                if (buffer.size > 0) "$s…(${body.contentLength()}B)" else s
            }
        } ?: "(no body)"

        LogManager.logDebug(
            "OKHTTP",
            "[$outputName] --> ${request.method} ${request.url}\n" +
                "Headers: ${request.headers.toMultimap()}\n" +
                "Body: $reqBodyStr"
        )

        val response = chain.proceed(request)

        // Log response (buffer body so it can still be consumed by caller)
        val respBody = response.body
        val respBodyStr = if (respBody != null) {
            val source = respBody.source()
            source.request(Long.MAX_VALUE)
            source.buffer.clone().readUtf8().take(500)
        } else "(no body)"

        LogManager.logDebug(
            "OKHTTP",
            "[$outputName] <-- ${response.code} ${response.message} ${request.url}\n" +
                "Headers: ${response.headers.toMultimap()}\n" +
                "Body: $respBodyStr"
        )

        return response
    }
}
