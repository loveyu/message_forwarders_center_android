package info.loveyu.mfca.input

import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import info.loveyu.mfca.config.CookieAuth
import info.loveyu.mfca.config.HttpInputConfig
import info.loveyu.mfca.config.HttpInputDsnParser
import info.loveyu.mfca.config.HttpInputParsedConfig
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.NetworkChecker
import org.json.JSONObject
import java.net.BindException

/**
 * HTTP 输入源 - DSN 协议配置
 *
 * DSN 格式: http://[user:pass@]host:port[?params]
 */
class HttpInput(
    private val httpConfig: HttpInputConfig
) : NanoHTTPD(parseListen(httpConfig.dsn), parsePort(httpConfig.dsn)), InputSource {

    private val parsedConfig: HttpInputParsedConfig
    override val inputName: String = httpConfig.name
    override val inputType: InputType = InputType.http

    private var running = false
    @Volatile private var error: String? = null
    private var messageListener: ((InputMessage) -> Unit)? = null

    init {
        parsedConfig = try {
            HttpInputDsnParser.parse(httpConfig.dsn)
        } catch (e: Exception) {
            error = "DSN 解析失败: ${e.message}"
            LogManager.appendLog("HTTP", "DSN parse error for $inputName: ${e.message}")
            // Fallback config so NanoHTTPD doesn't crash
            HttpInputParsedConfig(listen = "0.0.0.0", port = 0)
        }
    }

    override fun start() {
        if (error != null) {
            LogManager.appendLog("HTTP", "HTTP input $inputName skipped: ${error}")
            return
        }
        try {
            start(SOCKET_READ_TIMEOUT, false)
            running = true
            LogManager.appendLog("HTTP", "HTTP input started: $inputName on ${parsedConfig.listen}:${parsedConfig.port} paths=${httpConfig.paths}")
        } catch (e: BindException) {
            error = "端口 ${parsedConfig.port} 已被占用"
            LogManager.appendLog("HTTP", "HTTP input $inputName port conflict: ${parsedConfig.port} - ${e.message}")
        } catch (e: Exception) {
            error = "启动失败: ${e.message}"
            LogManager.appendLog("HTTP", "Failed to start HTTP input: $inputName - ${e.message}")
        }
    }

    override fun stop() {
        running = false
        try {
            super.stop()
            LogManager.appendLog("HTTP", "HTTP input stopped: $inputName")
        } catch (e: Exception) {
            LogManager.appendLog("HTTP", "Error stopping HTTP input: $inputName - ${e.message}")
        }
    }

    override fun isRunning(): Boolean = running

    override fun getError(): String? = error

    fun getParsedConfig(): HttpInputParsedConfig = parsedConfig

    override fun setOnMessageListener(listener: (InputMessage) -> Unit) {
        messageListener = listener
    }

    override fun serve(session: IHTTPSession): Response {
        // IP access control
        val remoteIp = session.remoteIpAddress
        if (!checkIpAccess(remoteIp)) {
            LogManager.appendLog("HTTP", "IP denied: $remoteIp for $inputName")
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                MIME_PLAINTEXT,
                "Forbidden"
            )
        }

        // Authentication - all configured methods must pass
        if (!authenticate(session)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                MIME_PLAINTEXT,
                "Unauthorized"
            )
        }

        // Multi-path matching (empty paths = allow all)
        val uri = session.uri ?: "/"
        if (httpConfig.paths.isNotEmpty() && !httpConfig.paths.contains(uri)) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Not Found"
            )
        }

        // Method check (empty methods = allow all)
        if (parsedConfig.methods.isNotEmpty()) {
            if (!parsedConfig.methods.contains(session.method.name.uppercase())) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not Found"
                )
            }
        }

        // Parse request
        try {
            // Read raw POST body before parseBody consumes the stream
            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
            val body: ByteArray = if (contentLength > 0) {
                val buffer = ByteArray(contentLength.toInt())
                session.inputStream.read(buffer)
                buffer
            } else {
                ByteArray(0)
            }

            val headersMap = mutableMapOf<String, String>()
            session.headers.forEach { (key, value) ->
                headersMap[key] = value
            }
            // Include matched path in headers
            headersMap["X-Matched-Path"] = uri

            // Parse query string
            session.queryParameterString?.let { queryStr ->
                headersMap["queryRaw"] = queryStr
                val queryJson = JSONObject()
                queryStr.split("&").forEach { pair ->
                    val kv = pair.split("=", limit = 2)
                    if (kv.size == 2) {
                        queryJson.put(java.net.URLDecoder.decode(kv[0], "UTF-8"), java.net.URLDecoder.decode(kv[1], "UTF-8"))
                    } else if (kv.isNotEmpty() && kv[0].isNotEmpty()) {
                        queryJson.put(java.net.URLDecoder.decode(kv[0], "UTF-8"), "")
                    }
                }
                headersMap["X-Query-Params"] = queryJson.toString()
            }

            val message = InputMessage(
                source = inputName,
                data = body,
                headers = headersMap
            )

            messageListener?.invoke(message)
            LogManager.appendLog("HTTP", "Message received from $inputName path=$uri")

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

    /**
     * IP 访问控制检查
     */
    private fun checkIpAccess(remoteIp: String): Boolean {
        // Deny list takes priority
        if (parsedConfig.denyIps.isNotEmpty()) {
            if (parsedConfig.denyIps.any { NetworkChecker.isIpInRange(remoteIp, it) }) {
                return false
            }
        }

        // Allow list - if specified, must match
        if (parsedConfig.allowIps.isNotEmpty()) {
            return parsedConfig.allowIps.any { NetworkChecker.isIpInRange(remoteIp, it) }
        }

        return true
    }

    /**
     * 多认证检查 - 所有已配置的认证方式必须全部通过
     */
    private fun authenticate(session: IHTTPSession): Boolean {
        var anyAuthConfigured = false

        parsedConfig.basicAuth?.let {
            anyAuthConfigured = true
            if (!authenticateBasic(session, it)) return false
        }

        parsedConfig.bearerAuth?.let {
            anyAuthConfigured = true
            if (!authenticateBearer(session, it)) return false
        }

        parsedConfig.queryAuth?.let {
            anyAuthConfigured = true
            if (!authenticateQuery(session, it)) return false
        }

        parsedConfig.cookieAuth?.let {
            anyAuthConfigured = true
            if (!authenticateCookie(session, it)) return false
        }

        // If no auth configured, allow all
        return true
    }

    private fun authenticateBasic(session: IHTTPSession, basicAuth: info.loveyu.mfca.config.BasicAuth): Boolean {
        val authHeader = session.headers["authorization"] ?: return false

        if (!authHeader.startsWith("Basic ")) return false

        val encoded = authHeader.removePrefix("Basic ")
        val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
        val parts = decoded.split(":", limit = 2)
        if (parts.size != 2) return false

        return parts[0] == basicAuth.username && parts[1] == basicAuth.password
    }

    private fun authenticateBearer(session: IHTTPSession, bearerAuth: info.loveyu.mfca.config.BearerAuth): Boolean {
        val authHeader = session.headers["authorization"] ?: return false
        return authHeader == "Bearer ${bearerAuth.token}"
    }

    private fun authenticateQuery(session: IHTTPSession, queryAuth: info.loveyu.mfca.config.QueryAuth): Boolean {
        val queryString = session.queryParameterString ?: return false
        return queryString.contains("${queryAuth.key}=${queryAuth.value}")
    }

    private fun authenticateCookie(session: IHTTPSession, cookieAuth: CookieAuth): Boolean {
        val cookieHeader = session.headers["cookie"] ?: return false
        // Parse cookies: "key1=value1; key2=value2"
        val cookies = cookieHeader.split(";").associate { cookie ->
            val kv = cookie.trim().split("=", limit = 2)
            if (kv.size == 2) kv[0].trim() to kv[1].trim() else kv[0].trim() to ""
        }
        return cookies[cookieAuth.key] == cookieAuth.value
    }

    companion object {
        private fun parseListen(dsn: String): String {
            return try {
                val uri = java.net.URI(dsn)
                uri.host ?: "0.0.0.0"
            } catch (e: Exception) {
                "0.0.0.0"
            }
        }

        private fun parsePort(dsn: String): Int {
            return try {
                val uri = java.net.URI(dsn)
                if (uri.port > 0) uri.port else 8080
            } catch (e: Exception) {
                8080
            }
        }
    }
}
