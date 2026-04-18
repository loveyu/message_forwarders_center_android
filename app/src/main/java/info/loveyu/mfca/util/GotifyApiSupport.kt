package info.loveyu.mfca.util

import info.loveyu.mfca.config.ReplayConfig
import info.loveyu.mfca.link.LinkManager
import java.net.URLDecoder

data class GotifyApiConfig(
    val baseUrl: String,
    val token: String,
    val applicationId: Int? = null
)

object GotifyApiSupport {

    fun resolveApiConfig(linkId: String, replay: ReplayConfig? = null): GotifyApiConfig? {
        val derived = LinkManager.getLinkConfig(linkId)?.dsn?.let { parseRestConfig(it) }
        val baseUrl = (replay?.baseUrl ?: derived?.first)?.trim()?.trimEnd('/')
        val token = (replay?.token ?: derived?.second)?.trim()
        if (baseUrl.isNullOrBlank() || token.isNullOrBlank()) {
            return null
        }
        return GotifyApiConfig(
            baseUrl = baseUrl,
            token = token,
            applicationId = replay?.applicationId
        )
    }

    /**
     * 从 Gotify WebSocket DSN 推导 REST API base URL 和 token。
     * 例如：wss://gotify.example.com?token=xxx -> ("https://gotify.example.com", "xxx")
     */
    fun parseRestConfig(dsn: String): Pair<String, String?> {
        var url = dsn
        url = when {
            url.startsWith("wss://") -> "https://" + url.removePrefix("wss://")
            url.startsWith("ws://") -> "http://" + url.removePrefix("ws://")
            else -> url
        }

        url = url.replace("/stream", "")

        var token: String? = null
        val queryStart = url.indexOf('?')
        val baseUrl = if (queryStart != -1) {
            val queryString = url.substring(queryStart + 1)
            queryString.split('&').forEach { param ->
                val kv = param.split('=', limit = 2)
                if (kv.size == 2 && kv[0] == "token") {
                    token = URLDecoder.decode(kv[1], "UTF-8")
                }
            }
            url.substring(0, queryStart)
        } else {
            url
        }

        return baseUrl.trimEnd('/') to token
    }
}
