package info.loveyu.mfca.config

import java.net.URI
import java.net.URLDecoder

/**
 * HTTP 输入 DSN 解析器
 *
 * DSN 格式: http://[user:pass@]host:port[?params]
 *
 * 支持的查询参数:
 *   bearerToken=xxx               - Bearer Token 认证
 *   queryTokenKey=k&queryTokenValue=v - Query 参数认证
 *   cookieTokenKey=k&cookieTokenValue=v - Cookie 认证
 *   allowIps=192.168.1.0/24,10.0.0.1 - 允许的 IP CIDR/精确地址
 *   denyIps=192.168.1.100          - 禁止的 IP CIDR/精确地址
 *   methods=GET,POST              - 允许的 HTTP 方法(逗号分隔，不区分大小写，空则不限)
 */
object HttpInputDsnParser {

    fun parse(dsn: String): HttpInputParsedConfig {
        val uri = URI(dsn)

        if (uri.scheme != "http") {
            throw ConfigLoadException("HTTP input DSN only supports http:// scheme, got: ${uri.scheme}")
        }

        val listen = uri.host ?: "0.0.0.0"
        val port = if (uri.port > 0) uri.port else 8080

        // Parse userinfo for basic auth
        val basicAuth = uri.userInfo?.let { userinfo ->
            val parts = userinfo.split(":", limit = 2)
            if (parts.size == 2) {
                BasicAuth(
                    username = URLDecoder.decode(parts[0], "UTF-8"),
                    password = URLDecoder.decode(parts[1], "UTF-8")
                )
            } else null
        }

        // Parse query parameters
        val params = parseQueryParams(uri.query)

        val bearerAuth = params["bearerToken"]?.let { BearerAuth(token = it) }

        val queryAuth = if (params.containsKey("queryTokenKey") && params.containsKey("queryTokenValue")) {
            QueryAuth(key = params["queryTokenKey"]!!, value = params["queryTokenValue"]!!)
        } else null

        val cookieAuth = if (params.containsKey("cookieTokenKey") && params.containsKey("cookieTokenValue")) {
            CookieAuth(key = params["cookieTokenKey"]!!, value = params["cookieTokenValue"]!!)
        } else null

        val allowIps = params["allowIps"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()

        val denyIps = params["denyIps"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()

        val methods = params["methods"]?.split(",")?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }
            ?: emptyList()

        return HttpInputParsedConfig(
            listen = listen,
            port = port,
            methods = methods,
            basicAuth = basicAuth,
            bearerAuth = bearerAuth,
            queryAuth = queryAuth,
            cookieAuth = cookieAuth,
            allowIps = allowIps,
            denyIps = denyIps
        )
    }

    private fun parseQueryParams(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()

        val result = mutableMapOf<String, String>()
        query.split("&").forEach { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                val key = URLDecoder.decode(kv[0].trim().replace("+", "%2B"), "UTF-8")
                val value = URLDecoder.decode(kv[1].trim().replace("+", "%2B"), "UTF-8")
                result[key] = value
            }
        }
        return result
    }
}
