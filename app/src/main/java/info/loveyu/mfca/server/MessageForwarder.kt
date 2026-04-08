package info.loveyu.mfca.server

import android.util.Log
import info.loveyu.mfca.constants.ApiConstants
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object MessageForwarder {
    private const val TAG = "MessageForwarder"

    fun forward(targetUrl: String, payload: String, callback: ((Boolean) -> Unit)? = null) {
        thread {
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

                Log.d(TAG, "Forwarded to $targetUrl, response: $responseCode")
                callback?.invoke(responseCode in 200..299)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to forward message to $targetUrl", e)
                callback?.invoke(false)
            }
        }
    }
}
