package info.loveyu.mfca.server

import info.loveyu.mfca.constants.ApiConstants
import info.loveyu.mfca.util.LogLevel
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object MessageForwarder {
    private const val TAG = "MessageForwarder"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(ApiConstants.CONNECT_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(ApiConstants.READ_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    fun forward(targetUrl: String, payload: String, callback: ((Boolean) -> Unit)? = null) {
        scope.launch {
            try {
                val body = payload.toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(targetUrl)
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                response.use {
                    LogManager.log(LogLevel.DEBUG, TAG, "Forwarded to $targetUrl, response: ${it.code}")
                    callback?.invoke(it.isSuccessful)
                }
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
