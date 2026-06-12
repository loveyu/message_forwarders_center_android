package info.loveyu.mfca.ui

import info.loveyu.mfca.config.models.HttpInputConfig
import info.loveyu.mfca.config.HttpInputDsnParser
import info.loveyu.mfca.config.models.HttpInputParsedConfig
import info.loveyu.mfca.config.models.LinkConfig
import info.loveyu.mfca.util.network.NetworkChecker
import java.net.URI

internal fun getLinkTypeString(config: LinkConfig): String {
    return when {
        config.dsn?.startsWith("mqtts") == true -> "MQTT (SSL)"
        config.dsn?.startsWith("mqtt") == true -> "MQTT"
        config.dsn?.startsWith("wss") == true -> "WebSocket (SSL)"
        config.dsn?.startsWith("ws") == true -> "WebSocket"
        config.dsn?.startsWith("ssl") == true -> "TCP (SSL)"
        config.dsn?.startsWith("tcp") == true -> "TCP"
        config.dsn?.startsWith("https") == true -> "HTTPS"
        config.dsn?.startsWith("http") == true -> "HTTP"
        config.dsn?.startsWith("wss") == true -> "WebSocket (SSL)"
        config.dsn?.startsWith("ws") == true -> "WebSocket"
        else -> "Unknown"
    }
}

internal data class HttpAccessInfo(
    val scheme: String,
    val listenHost: String,
    val port: Int,
    val accessUrls: List<String>
)

internal fun isWildcardHost(host: String): Boolean {
    return host == "0.0.0.0" || host == "::" || host == "*"
}

internal fun normalizeHttpPath(path: String): String {
    if (path.isBlank()) return ""
    if (path == "/") return "/"
    return if (path.startsWith("/")) path else "/$path"
}

internal fun resolveHttpHosts(listenHost: String): List<String> {
    val normalizedHost = listenHost.removePrefix("[").removeSuffix("]").trim()
    return when {
        normalizedHost.isEmpty() -> listOf("0.0.0.0")
        isWildcardHost(normalizedHost) ->
            NetworkChecker.getAllLocalIpv4Addresses().ifEmpty { listOf(normalizedHost) }
        normalizedHost.equals("localhost", ignoreCase = true) -> listOf("127.0.0.1")
        else -> listOf(normalizedHost)
    }
}

internal fun buildHttpAccessUrls(
    scheme: String,
    listenHost: String,
    port: Int,
    paths: List<String>
): List<String> {
    val normalizedPaths = if (paths.isEmpty()) listOf("") else paths.map(::normalizeHttpPath)
    return resolveHttpHosts(listenHost)
        .flatMap { host ->
            normalizedPaths.map { path -> "$scheme://$host:$port$path" }
        }
        .distinct()
}

internal fun parseHttpAccessInfo(dsn: String?, paths: List<String>): HttpAccessInfo {
    val fallbackScheme = "http"
    val fallbackHost = "0.0.0.0"
    val (scheme, host, port) = try {
        val uri = URI(dsn ?: "")
        val resolvedScheme = uri.scheme?.lowercase() ?: fallbackScheme
        Triple(
            resolvedScheme,
            uri.host ?: fallbackHost,
            if (uri.port > 0) uri.port else if (resolvedScheme == "https") 443 else 8080
        )
    } catch (_: Exception) {
        Triple(fallbackScheme, fallbackHost, 8080)
    }
    return HttpAccessInfo(
        scheme = scheme,
        listenHost = host,
        port = port,
        accessUrls = buildHttpAccessUrls(scheme, host, port, paths)
    )
}

internal fun parseHttpInputConfigSafely(httpConfig: HttpInputConfig): HttpInputParsedConfig? {
    return try {
        HttpInputDsnParser.parse(httpConfig.dsn)
    } catch (_: Exception) {
        null
    }
}

internal fun StringBuilder.appendHttpAccessDetails(accessInfo: HttpAccessInfo) {
    if (accessInfo.accessUrls.isNotEmpty()) {
        append("\nAddresses:")
        accessInfo.accessUrls.forEach { append("\n$it") }
    } else {
        append("\nListen: ${accessInfo.listenHost}:${accessInfo.port}")
    }
}
