package info.loveyu.mfca.pipeline

import android.content.Context
import info.loveyu.mfca.input.InputManager
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.util.GotifyApiSupport
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
        val appid = json.optInt("appid", -1)
        if (appid == -1) {
            LogManager.logDebug("ENRICH", "No appid found in message, skipping gotifyIcon enrich")
            return null
        }

        // Get icon URL from cache or API
        val iconUrl = getAppIconUrl(parameter, appid)
        if (iconUrl == null) {
            LogManager.logDebug("ENRICH", "No icon URL found for appid=$appid on parameter=$parameter")
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
    private suspend fun getAppIconUrl(parameter: String, appid: Int): String? {
        // Check memory cache
        val cached = appIconCache[parameter]
        if (cached != null) {
            val (cachedAt, iconMap) = cached
            if (System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
                return iconMap[appid]
            }
            appIconCache.remove(parameter)
        }

        // Fetch from API
        val iconMap = fetchAppIcons(parameter) ?: return null
        return iconMap[appid]
    }

    /**
     * 调用 Gotify REST API 获取应用列表并构建 appid -> iconUrl 映射。
     *
     * 参数 [parameter] 可以是：
     * - input 名称（inputId）：从该 input 的 replayConfig 中读取 baseUrl/token
     * - link ID（linkId）：从 link DSN 中解析 baseUrl/token（适用于 WebSocket link）
     */
    private suspend fun fetchAppIcons(parameter: String): Map<Int, String>? {
        val apiConfig = resolveApiConfig(parameter) ?: return null

        return try {
            val result = withContext(Dispatchers.IO) {
                val url = "${apiConfig.baseUrl}/application"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-Gotify-Key", apiConfig.token)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    LogManager.logWarn("ENRICH", "Gotify API returned ${response.code} for $url")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                parseAppList(apiConfig.baseUrl, body)
            }

            if (result != null) {
                appIconCache[parameter] = System.currentTimeMillis() to result
                LogManager.logDebug("ENRICH", "Cached ${result.size} app icons for parameter=$parameter")
            }
            result
        } catch (e: Exception) {
            LogManager.logWarn("ENRICH", "Failed to fetch Gotify apps: ${e.message}")
            null
        }
    }

    /**
     * 根据参数解析 Gotify API 配置：
     * 1. 若 parameter 是 input 名称且该 input 有 replayConfig，从 replayConfig 读取
     * 2. 否则将 parameter 视为 linkId，从 link DSN 解析（WebSocket link）
     */
    private fun resolveApiConfig(parameter: String): info.loveyu.mfca.util.GotifyApiConfig? {
        val inputConfig = InputManager.getLinkInputConfigByName(parameter)
        if (inputConfig?.replay != null) {
            val apiConfig = GotifyApiSupport.resolveApiConfig(inputConfig.linkId, inputConfig.replay)
            if (apiConfig == null) {
                LogManager.logWarn("ENRICH", "No Gotify API config in replay config for input=$parameter")
            }
            return apiConfig
        }

        if (LinkManager.getLinkConfig(parameter) == null) {
            LogManager.logWarn("ENRICH", "Neither input nor link found for parameter=$parameter")
            return null
        }
        val apiConfig = GotifyApiSupport.resolveApiConfig(parameter)
        if (apiConfig == null) {
            LogManager.logWarn("ENRICH", "No Gotify API config found for link=$parameter")
        }
        return apiConfig
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
