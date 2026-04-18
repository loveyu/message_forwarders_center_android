package info.loveyu.mfca.input

import fi.iki.elonen.NanoHTTPD
import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.util.LogManager
import java.net.BindException

/**
 * 共享 HTTP 输入服务器
 *
 * 多个 HttpVirtualInput 共用同一个 NanoHTTPD 服务器实例。
 * 请求按 YAML 定义顺序遍历 virtual inputs，精确匹配 paths 后执行对应 input 的处理逻辑。
 */
class SharedHttpInput(
    linkConfig: LinkConfig
) : NanoHTTPD(parseListenFromLink(linkConfig), parsePortFromLink(linkConfig)), InputSource {

    private val configuredListen = parseListenFromLink(linkConfig)
    private val configuredPort = parsePortFromLink(linkConfig)
    private val virtualInputs = mutableListOf<HttpVirtualInput>()
    private var running = false
    @Volatile private var fatalError: String? = null
    @Volatile private var lastError: String? = null

    override val inputName: String = "__shared_http_${linkConfig.id}"
    override val inputType: InputType = InputType.http

    private var messageListener: ((InputMessage) -> Unit)? = null

    fun addVirtualInput(virtualInput: HttpVirtualInput) {
        virtualInputs.add(virtualInput)
    }

    fun getVirtualInputs(): List<HttpVirtualInput> = virtualInputs.toList()

    override fun start() {
        if (fatalError != null) {
            LogManager.logWarn("HTTP", "Shared HTTP server skipped: $fatalError")
            return
        }
        try {
            start(SOCKET_READ_TIMEOUT, false)
            running = true
            lastError = null
            LogManager.logDebug("HTTP", "Shared HTTP server started with ${virtualInputs.size} virtual inputs on ${configuredListen}:${configuredPort}")
        } catch (e: BindException) {
            running = false
            lastError = "端口 ${configuredPort} 暂时被占用，将自动重试"
            LogManager.logWarn("HTTP", "Shared HTTP server bind conflict on ${configuredListen}:${configuredPort}, will retry: ${e.message}")
        } catch (e: Exception) {
            running = false
            lastError = "启动失败: ${e.message}"
            LogManager.logError("HTTP", "Failed to start shared HTTP server: ${e.message}")
        }
    }

    override fun stop() {
        running = false
        try {
            super.stop()
            if (fatalError == null) {
                lastError = null
            }
            LogManager.logDebug("HTTP", "Shared HTTP server stopped")
        } catch (e: Exception) {
            LogManager.logError("HTTP", "Error stopping shared HTTP server: ${e.message}")
        }
    }

    override fun isRunning(): Boolean = running

    override fun getError(): String? = fatalError ?: lastError

    override fun hasFatalError(): Boolean = fatalError != null

    override fun setOnMessageListener(listener: (InputMessage) -> Unit) {
        messageListener = listener
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        // Iterate virtual inputs in YAML definition order
        for (virtualInput in virtualInputs) {
            val response = virtualInput.matchAndHandle(session)
            if (response != null) {
                return response
            }
        }

        // No virtual input matched
        if (LogManager.isDebugEnabled()) {
            LogManager.logDebug("HTTP", "No virtual input matched: $uri from ${session.remoteIpAddress}")
        }
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            MIME_PLAINTEXT,
            "Not Found"
        )
    }

    companion object {
        private fun parseListenFromLink(linkConfig: LinkConfig): String {
            val dsn = linkConfig.dsn ?: return "0.0.0.0"
            return try {
                val uri = java.net.URI(dsn)
                uri.host ?: "0.0.0.0"
            } catch (e: Exception) {
                "0.0.0.0"
            }
        }

        private fun parsePortFromLink(linkConfig: LinkConfig): Int {
            val dsn = linkConfig.dsn ?: return 8080
            return try {
                val uri = java.net.URI(dsn)
                if (uri.port > 0) uri.port else 8080
            } catch (e: Exception) {
                8080
            }
        }
    }
}
