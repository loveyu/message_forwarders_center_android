package info.loveyu.mfca.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import info.loveyu.mfca.constants.ApiConstants
import org.json.JSONObject
import java.io.IOException

class HttpServer(
    private val port: Int,
    private val onMessageReceived: (String) -> Unit
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "HttpServer"
    }

    @Throws(IOException::class)
    fun startServer() {
        start(SOCKET_READ_TIMEOUT, false)
        Log.i(TAG, "HTTP server started on port $port")
    }

    fun stopServer() {
        stop()
        Log.i(TAG, "HTTP server stopped")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        return when {
            uri == ApiConstants.ENDPOINT_HEALTH && method == Method.GET -> {
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"ok"}"""
                )
            }

            uri == ApiConstants.ENDPOINT_VERSION && method == Method.GET -> {
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"version":"${ApiConstants.VERSION_NAME}"}"""
                )
            }

            uri == ApiConstants.ENDPOINT_MESSAGE && method == Method.POST -> {
                handlePostMessage(session)
            }

            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}""")
            }
        }
    }

    private fun handlePostMessage(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: ""

            if (body.isEmpty()) {
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"error":"empty body"}"""
                )
            } else {
                onMessageReceived(body)
                Log.d(TAG, "Received message: ${body.take(200)}")
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"received"}"""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling POST message", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"${e.message}"}"""
            )
        }
    }
}
