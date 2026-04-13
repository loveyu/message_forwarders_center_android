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
            LogManager.logError("HTTP", "DSN parse error for $inputName: ${e.message}")
            // Fallback config so NanoHTTPD doesn't crash
            HttpInputParsedConfig(listen = "0.0.0.0", port = 0)
        }
    }

    override fun start() {
        if (error != null) {
            LogManager.logWarn("HTTP", "HTTP input $inputName skipped: $error")
            return
        }
        try {
            start(SOCKET_READ_TIMEOUT, false)
            running = true
            LogManager.log("HTTP", "HTTP input started: $inputName on ${parsedConfig.listen}:${parsedConfig.port} paths=${httpConfig.paths}")
        } catch (e: BindException) {
            error = "端口 ${parsedConfig.port} 已被占用"
            LogManager.logError("HTTP", "HTTP input $inputName port conflict: ${parsedConfig.port} - ${e.message}")
        } catch (e: Exception) {
            error = "启动失败: ${e.message}"
            LogManager.logError("HTTP", "Failed to start HTTP input: $inputName - ${e.message}")
        }
    }

    override fun stop() {
        running = false
        try {
            super.stop()
            LogManager.log("HTTP", "HTTP input stopped: $inputName")
        } catch (e: Exception) {
            LogManager.logError("HTTP", "Error stopping HTTP input: $inputName - ${e.message}")
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
        if (!checkIpAccess(remoteIp, parsedConfig)) {
            LogManager.log("HTTP", "IP denied: $remoteIp for $inputName")
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                MIME_PLAINTEXT,
                "Forbidden"
            )
        }

        // Authentication - all configured methods must pass
        if (!authenticate(session, parsedConfig)) {
            LogManager.logWarn("HTTP", "Auth failed for $inputName from $remoteIp")
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                MIME_PLAINTEXT,
                "Unauthorized"
            )
        }

        // Multi-path matching (empty paths = allow all)
        val uri = session.uri ?: "/"
        if (httpConfig.paths.isNotEmpty() && !httpConfig.paths.contains(uri)) {
            if (LogManager.isDebugEnabled()) {
                LogManager.logDebug("HTTP", "Path not matched: $uri for $inputName (allowed: ${httpConfig.paths})")
            }
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Not Found"
            )
        }

        // Method check (empty methods = allow all)
        if (parsedConfig.methods.isNotEmpty()) {
            if (!parsedConfig.methods.contains(session.method.name.uppercase())) {
                LogManager.logDebug("HTTP", "Method ${session.method} not allowed for $inputName (allowed: ${parsedConfig.methods})")
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not Found"
                )
            }
        }

        return handleRequest(session, uri, inputName, parsedConfig, messageListener)
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

        /**
         * IP 访问控制检查
         */
        fun checkIpAccess(remoteIp: String, parsedConfig: HttpInputParsedConfig): Boolean {
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
        fun authenticate(session: NanoHTTPD.IHTTPSession, parsedConfig: HttpInputParsedConfig): Boolean {
            parsedConfig.basicAuth?.let {
                if (!authenticateBasic(session, it)) return false
            }

            parsedConfig.bearerAuth?.let {
                if (!authenticateBearer(session, it)) return false
            }

            parsedConfig.queryAuth?.let {
                if (!authenticateQuery(session, it)) return false
            }

            parsedConfig.cookieAuth?.let {
                if (!authenticateCookie(session, it)) return false
            }

            return true
        }

        private fun authenticateBasic(session: NanoHTTPD.IHTTPSession, basicAuth: info.loveyu.mfca.config.BasicAuth): Boolean {
            val authHeader = session.headers["authorization"] ?: return false

            if (!authHeader.startsWith("Basic ")) return false

            val encoded = authHeader.removePrefix("Basic ")
            val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
            val parts = decoded.split(":", limit = 2)
            if (parts.size != 2) return false

            return parts[0] == basicAuth.username && parts[1] == basicAuth.password
        }

        private fun authenticateBearer(session: NanoHTTPD.IHTTPSession, bearerAuth: info.loveyu.mfca.config.BearerAuth): Boolean {
            val authHeader = session.headers["authorization"] ?: return false
            return authHeader == "Bearer ${bearerAuth.token}"
        }

        private fun authenticateQuery(session: NanoHTTPD.IHTTPSession, queryAuth: info.loveyu.mfca.config.QueryAuth): Boolean {
            val queryString = session.queryParameterString ?: return false
            return queryString.contains("${queryAuth.key}=${queryAuth.value}")
        }

        private fun authenticateCookie(session: NanoHTTPD.IHTTPSession, cookieAuth: CookieAuth): Boolean {
            val cookieHeader = session.headers["cookie"] ?: return false
            // Parse cookies: "key1=value1; key2=value2"
            val cookies = cookieHeader.split(";").associate { cookie ->
                val kv = cookie.trim().split("=", limit = 2)
                if (kv.size == 2) kv[0].trim() to kv[1].trim() else kv[0].trim() to ""
            }
            return cookies[cookieAuth.key] == cookieAuth.value
        }

        /**
         * Handle a request: parse body, build message, invoke listener, return response.
         */
        fun handleRequest(
            session: NanoHTTPD.IHTTPSession,
            uri: String,
            sourceName: String,
            parsedConfig: HttpInputParsedConfig,
            messageListener: ((InputMessage) -> Unit)?
        ): NanoHTTPD.Response {
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
                    source = sourceName,
                    data = body,
                    headers = headersMap
                )

                if (messageListener != null) {
                    messageListener.invoke(message)
                } else {
                    LogManager.logWarn("HTTP", "No message listener for $sourceName, message dropped (path=$uri)")
                }
                LogManager.log("HTTP", "Message received from $sourceName path=$uri (${body.size} bytes)")

                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "OK"
                )
            } catch (e: Exception) {
                LogManager.logError("HTTP", "Error processing request from $sourceName: ${e.message}")
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Internal Error"
                )
            }
        }
    }
}

/**
 * 虚拟 HTTP 输入 - 用于共享模式下的独立路由单元
 *
 * 不继承 NanoHTTPD，仅持有 config 和 messageListener。
 * start()/stop() 为空操作（服务器由 SharedHttpInput 管理）。
 */
class HttpVirtualInput(
    private val httpConfig: HttpInputConfig
) : InputSource {

    private val parsedConfig: HttpInputParsedConfig
    override val inputName: String = httpConfig.name
    override val inputType: InputType = InputType.http

    @Volatile private var error: String? = null
    private var messageListener: ((InputMessage) -> Unit)? = null

    init {
        parsedConfig = try {
            HttpInputDsnParser.parse(httpConfig.dsn)
        } catch (e: Exception) {
            error = "DSN 解析失败: ${e.message}"
            LogManager.logError("HTTP", "DSN parse error for virtual input $inputName: ${e.message}")
            HttpInputParsedConfig(listen = "0.0.0.0", port = 0)
        }
    }

    // Lifecycle managed by SharedHttpInput
    override fun start() {}
    override fun stop() {}
    override fun isRunning(): Boolean = true

    override fun getError(): String? = error

    override fun setOnMessageListener(listener: (InputMessage) -> Unit) {
        messageListener = listener
    }

    fun getParsedConfig(): HttpInputParsedConfig = parsedConfig

    /**
     * Check if this virtual input matches the given URI path.
     */
    fun matchesPath(uri: String): Boolean {
        return httpConfig.paths.isEmpty() || httpConfig.paths.contains(uri)
    }

    /**
     * Attempt to handle a request. Returns a NanoHTTPD Response if this input
     * matches and processes the request, or null if no match.
     */
    fun matchAndHandle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val uri = session.uri ?: "/"

        // Path matching
        if (!matchesPath(uri)) return null

        // IP access control
        val remoteIp = session.remoteIpAddress
        if (!HttpInput.checkIpAccess(remoteIp, parsedConfig)) {
            LogManager.logWarn("HTTP", "IP denied: $remoteIp for $inputName")
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.FORBIDDEN,
                NanoHTTPD.MIME_PLAINTEXT,
                "Forbidden"
            )
        }

        // Authentication
        if (!HttpInput.authenticate(session, parsedConfig)) {
            LogManager.logWarn("HTTP", "Auth failed for virtual input $inputName from $remoteIp")
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.UNAUTHORIZED,
                NanoHTTPD.MIME_PLAINTEXT,
                "Unauthorized"
            )
        }

        // Method check
        if (parsedConfig.methods.isNotEmpty()) {
            if (!parsedConfig.methods.contains(session.method.name.uppercase())) {
                LogManager.logDebug("HTTP", "Method ${session.method} not allowed for virtual input $inputName")
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Not Found"
                )
            }
        }

        return HttpInput.handleRequest(session, uri, inputName, parsedConfig, messageListener)
    }
}
