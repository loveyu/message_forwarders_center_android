package info.loveyu.mfca.server

import info.loveyu.mfca.constants.ApiConstants
import info.loveyu.mfca.util.LogLevel
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

object MessageForwarder {
    private const val TAG = "MessageForwarder"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun forward(targetUrl: String, payload: String, callback: ((Boolean) -> Unit)? = null) {
        scope.launch {
            try {
                val url = URL(targetUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = ApiConstants.CONNECT_TIMEOUT
                connection.readTimeout = ApiConstants.READ_TIMEOUT
                connection.doOutput = true

                connection.outputStream.use { os ->
                    val input = payload.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                val responseCode = connection.responseCode
                connection.disconnect()

                LogManager.log(LogLevel.DEBUG, TAG, "Forwarded to $targetUrl, response: $responseCode")
                callback?.invoke(responseCode in 200..299)
            } catch (e: Exception) {
                LogManager.log(LogLevel.ERROR, TAG, "Failed to forward message to $targetUrl", e)
                callback?.invoke(false)
            }
        }
    }

    fun shutdown() {
        scope.cancel()
    }
}
