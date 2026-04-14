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
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP 输出
 */
class HttpOutput(
    override val name: String,
    private val config: HttpOutputConfig
) : Output {

    override val type: OutputType = OutputType.http

    @Volatile
    private var available = true

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
                        LogManager.log("HTTP", "HTTP output $name succeeded (${result.responseCode})")
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
                        LogManager.logDebug("HTTP", "Retry $attempt/$maxAttempts after ${interval}ms")
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
            val url = URL(config.url)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = config.method
            connection.doOutput = true
            connection.doInput = true
            connection.connectTimeout = config.timeout.millis.toInt()
            connection.readTimeout = config.timeout.millis.toInt()
            connection.setRequestProperty("Content-Type", "application/octet-stream")

            connection.outputStream.use { output ->
                output.write(item.data)
            }

            val responseCode = connection.responseCode
            connection.disconnect()

            if (responseCode in 200..299) {
                OutputResult.Success(responseCode)
            } else {
                OutputResult.Failure("HTTP $responseCode")
            }
        } catch (e: Exception) {
            available = false
            OutputResult.Failure(e.message ?: "Unknown error", e)
        }
    }

    override fun isAvailable(): Boolean = available

    fun shutdown() {
        scope.cancel()
    }
}
