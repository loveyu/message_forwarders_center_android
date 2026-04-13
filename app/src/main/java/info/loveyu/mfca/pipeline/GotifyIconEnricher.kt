package info.loveyu.mfca.pipeline

import android.content.Context
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Gotify 应用图标丰富器
 *
 * 通过 Gotify REST API 获取应用图标 URL 并注入到消息 JSON 的 icon 字段。
 * 使用内存缓存减少 API 调用，缓存 24 小时后自动失效。
 *
 * Gotify 消息格式: {"id":1, "appid":5, "message":"...", "title":"...", ...}
 * Gotify 应用 API: GET /application (Header: X-Gotify-Key: <token>)
 * 应用响应: [{"id":5, "image":"image/image.jpeg", ...}, ...]
 */
class GotifyIconEnricher(private val context: Context?) : Enricher {

    override val type = "gotifyIcon"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // 内存缓存: linkId -> (appid -> iconUrl)
    private val appIconCache = ConcurrentHashMap<String, Pair<Long, Map<Int, String>>>()

    companion object {
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    override suspend fun enrich(json: JSONObject, parameter: String): JSONObject? {
        val linkId = parameter
        val appid = json.optInt("appid", -1)
        if (appid == -1) {
            LogManager.logDebug("ENRICH", "No appid found in message, skipping gotifyIcon enrich")
            return null
        }

        // Get icon URL from cache or API
        val iconUrl = getAppIconUrl(linkId, appid)
        if (iconUrl == null) {
            LogManager.logDebug("ENRICH", "No icon URL found for appid=$appid on link=$linkId")
            return null
        }

        // Inject icon field
        json.put("icon", iconUrl)
        LogManager.logDebug("ENRICH", "Injected icon URL for appid=$appid: $iconUrl")
        return json
    }

    /**
     * 获取应用图标 URL，优先从缓存读取
     */
    private suspend fun getAppIconUrl(linkId: String, appid: Int): String? {
        // Check memory cache
        val cached = appIconCache[linkId]
        if (cached != null) {
            val (cachedAt, iconMap) = cached
            if (System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
                return iconMap[appid]
            }
            appIconCache.remove(linkId)
        }

        // Fetch from API
        val iconMap = fetchAppIcons(linkId) ?: return null
        return iconMap[appid]
    }

    /**
     * 调用 Gotify REST API 获取应用列表并构建 appid -> iconUrl 映射
     */
    private suspend fun fetchAppIcons(linkId: String): Map<Int, String>? {
        val linkConfig = LinkManager.getLinkConfig(linkId)
        if (linkConfig == null) {
            LogManager.logWarn("ENRICH", "Link not found: $linkId")
            return null
        }

        val dsn = linkConfig.dsn
        if (dsn == null) {
            LogManager.logWarn("ENRICH", "Link $linkId has no DSN")
            return null
        }

        // Derive REST API base URL and token from WebSocket DSN
        // wss://gotify.example.com?token=xxx -> https://gotify.example.com
        val (baseUrl, token) = parseGotifyRestConfig(dsn)

        if (token == null) {
            LogManager.logWarn("ENRICH", "No token found in DSN for link $linkId")
            return null
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                val url = "$baseUrl/application"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-Gotify-Key", token)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    LogManager.logWarn("ENRICH", "Gotify API returned ${response.code} for $url")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                parseAppList(baseUrl, body)
            }

            if (result != null) {
                appIconCache[linkId] = System.currentTimeMillis() to result
                LogManager.logDebug("ENRICH", "Cached ${result.size} app icons for link $linkId")
            }
            result
        } catch (e: Exception) {
            LogManager.logWarn("ENRICH", "Failed to fetch Gotify apps: ${e.message}")
            null
        }
    }

    /**
     * 从 WebSocket DSN 推导 REST API base URL 和 token
     * wss://gotify.example.com?token=xxx -> ("https://gotify.example.com", "xxx")
     */
    private fun parseGotifyRestConfig(dsn: String): Pair<String, String?> {
        var url = dsn

        // Replace protocol
        url = when {
            url.startsWith("wss://") -> "https://" + url.removePrefix("wss://")
            url.startsWith("ws://") -> "http://" + url.removePrefix("ws://")
            else -> url
        }

        // Remove /stream path if present
        url = url.replace("/stream", "")

        // Parse out token from query params
        var token: String? = null
        val queryStart = url.indexOf('?')
        val baseUrl = if (queryStart != -1) {
            val queryString = url.substring(queryStart + 1)
            queryString.split('&').forEach { param ->
                val kv = param.split('=', limit = 2)
                if (kv.size == 2 && kv[0] == "token") {
                    token = java.net.URLDecoder.decode(kv[1], "UTF-8")
                }
            }
            url.substring(0, queryStart)
        } else {
            url
        }

        return baseUrl.trimEnd('/') to token
    }

    /**
     * 解析 Gotify 应用列表 API 响应
     * 响应格式: [{"id":5, "image":"image/image.jpeg", "name":"MyApp", ...}, ...]
     */
    private fun parseAppList(baseUrl: String, responseBody: String): Map<Int, String> {
        val iconMap = mutableMapOf<Int, String>()
        try {
            val apps = org.json.JSONArray(responseBody)
            for (i in 0 until apps.length()) {
                val app = apps.getJSONObject(i)
                val id = app.getInt("id")
                val image = app.optString("image", "")
                if (image.isNotEmpty()) {
                    iconMap[id] = "$baseUrl/$image"
                }
            }
        } catch (e: Exception) {
            LogManager.logWarn("ENRICH", "Failed to parse Gotify app list: ${e.message}")
        }
        return iconMap
    }

    /**
     * 清理内存缓存
     */
    fun clearCache() {
        appIconCache.clear()
        LogManager.logDebug("ENRICH", "GotifyIconEnricher cache cleared")
    }
}
