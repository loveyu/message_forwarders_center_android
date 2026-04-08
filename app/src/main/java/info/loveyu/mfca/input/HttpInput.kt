package info.loveyu.mfca.input

import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import info.loveyu.mfca.config.HttpAuthType
import info.loveyu.mfca.config.HttpInputConfig
import info.loveyu.mfca.util.LogManager

/**
 * HTTP 输入源
 */
class HttpInput(
    private val httpConfig: HttpInputConfig
) : NanoHTTPD(httpConfig.listen, httpConfig.port), InputSource {

    override val inputName: String = httpConfig.name
    override val inputType: InputType = InputType.http

    private var running = false
    private var messageListener: ((InputMessage) -> Unit)? = null

    override fun start() {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            running = true
            LogManager.appendLog("HTTP", "HTTP input started: $inputName on ${httpConfig.listen}:${httpConfig.port}${httpConfig.path}")
        } catch (e: Exception) {
            LogManager.appendLog("HTTP", "Failed to start HTTP input: $inputName - ${e.message}")
        }
    }

    override fun stop() {
        try {
            stop()
            running = false
            LogManager.appendLog("HTTP", "HTTP input stopped: $inputName")
        } catch (e: Exception) {
            LogManager.appendLog("HTTP", "Error stopping HTTP input: $inputName - ${e.message}")
        }
    }

    override fun isRunning(): Boolean = running

    override fun setOnMessageListener(listener: (InputMessage) -> Unit) {
        messageListener = listener
    }

    override fun serve(session: IHTTPSession): Response {
        // Check authentication
        if (!authenticate(session)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                MIME_PLAINTEXT,
                "Unauthorized"
            )
        }

        // Only handle the configured path
        val uri = session.uri ?: "/"
        val method = session.method

        if (uri != httpConfig.path || method != Method.POST) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Not Found"
            )
        }

        // Parse POST data
        try {
            val files = HashMap<String, String>()
            session.parseBody(files)

            val body = files["postData"] ?: ""

            val headersMap = mutableMapOf<String, String>()
            session.headers.forEach { (key, values) ->
                headersMap[key] = values.firstOrNull()?.toString() ?: ""
            }

            val message = InputMessage(
                source = inputName,
                data = body.toByteArray(),
                headers = headersMap
            )

            messageListener?.invoke(message)
            LogManager.appendLog("HTTP", "Message received from $inputName")

            return newFixedLengthResponse(
                Response.Status.OK,
                MIME_PLAINTEXT,
                "OK"
            )
        } catch (e: Exception) {
            LogManager.appendLog("HTTP", "Error processing request: ${e.message}")
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Internal Error"
            )
        }
    }

    private fun authenticate(session: IHTTPSession): Boolean {
        val auth = httpConfig.auth ?: return true

        return when (auth.type) {
            HttpAuthType.basic -> authenticateBasic(session)
            HttpAuthType.bearer -> authenticateBearer(session)
            HttpAuthType.query -> authenticateQuery(session)
        }
    }

    private fun authenticateBasic(session: IHTTPSession): Boolean {
        val basicAuth = httpConfig.auth?.basic ?: return true
        val authHeader = session.headers["authorization"] ?: return false

        if (!authHeader.startsWith("Basic ")) return false

        val encoded = authHeader.removePrefix("Basic ")
        val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
        val parts = decoded.split(":", limit = 2)
        if (parts.size != 2) return false

        return parts[0] == basicAuth.username && parts[1] == basicAuth.password
    }

    private fun authenticateBearer(session: IHTTPSession): Boolean {
        val bearerAuth = httpConfig.auth?.bearer ?: return true
        val authHeader = session.headers["authorization"] ?: return false

        return authHeader == "Bearer ${bearerAuth.token}"
    }

    private fun authenticateQuery(session: IHTTPSession): Boolean {
        val queryAuth = httpConfig.auth?.query ?: return true
        val queryString = session.queryParameterString ?: return false

        return queryString.contains("${queryAuth.key}=${queryAuth.value}")
    }
}
