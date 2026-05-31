package info.loveyu.mfca.vpn

import android.content.Context
import info.loveyu.mfca.config.VpnInputConfig
import info.loveyu.mfca.util.LogManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object VpnConfigCacheManager {

    private data class CacheMeta(
        val lastUpdatedMs: Long,
        val contentHash: String,
    )

    fun inspect(context: Context, config: VpnInputConfig): VpnConfigCacheState {
        val source = sourceFile(context, config.name)
        val meta = loadMeta(context, config.name)
        val nextRefreshMs =
            if (config.refreshIntervalMs > 0 && meta != null) {
                meta.lastUpdatedMs + config.refreshIntervalMs
            } else {
                null
            }
        return VpnConfigCacheState(
            isCached = source.exists() && meta != null,
            lastUpdatedMs = meta?.lastUpdatedMs,
            nextRefreshMs = nextRefreshMs,
            filePath = source.absolutePath.takeIf { source.exists() },
        )
    }

    /**
     * Downloads the remote config and caches it.
     * Returns true if the content changed (or was newly downloaded), false if unchanged.
     */
    fun downloadConfig(context: Context, config: VpnInputConfig): Result<Boolean> {
        return runCatching {
            require(
                config.configUrl.startsWith("http://") ||
                    config.configUrl.startsWith("https://"),
            ) {
                "configUrl 仅支持 http/https"
            }
            cacheDir(context).mkdirs()
            val content = fetchContent(config.configUrl)
            val newHash = sha256(content)
            val oldMeta = loadMeta(context, config.name)
            val changed = oldMeta?.contentHash != newHash
            sourceFile(context, config.name).writeText(content)
            saveMeta(
                context,
                config.name,
                CacheMeta(lastUpdatedMs = System.currentTimeMillis(), contentHash = newHash),
            )
            if (changed) {
                LogManager.logInfo("VPN", "Config cache updated for ${config.name}: hash=$newHash")
            } else {
                LogManager.logInfo("VPN", "Config cache refreshed (unchanged) for ${config.name}")
            }
            changed
        }
    }

    fun deleteCache(context: Context, candidateName: String): Result<Unit> {
        return runCatching {
            sourceFile(context, candidateName).delete()
            metaFile(context, candidateName).delete()
            LogManager.logInfo("VPN", "Config cache deleted for $candidateName")
        }
    }

    fun getCachedSourceFile(context: Context, candidateName: String): File? {
        return sourceFile(context, candidateName).takeIf { it.exists() }
    }

    private fun fetchContent(url: String): String {
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "FlowGate-Android")
            }
        try {
            if (connection.responseCode !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IllegalStateException(
                    "配置下载失败: HTTP ${connection.responseCode}${if (error.isNullOrBlank()) "" else " - $error"}",
                )
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheDir(context: Context) = File(context.filesDir, "vpn/config_cache")

    private fun sourceFile(context: Context, name: String) =
        File(cacheDir(context), "${sanitize(name)}.yaml")

    private fun metaFile(context: Context, name: String) =
        File(cacheDir(context), "${sanitize(name)}.meta.json")

    private fun loadMeta(context: Context, name: String): CacheMeta? {
        return runCatching {
            val json = JSONObject(metaFile(context, name).readText())
            CacheMeta(
                lastUpdatedMs = json.getLong("lastUpdatedMs"),
                contentHash = json.getString("contentHash"),
            )
        }.getOrNull()
    }

    private fun saveMeta(context: Context, name: String, meta: CacheMeta) {
        val json = JSONObject()
        json.put("lastUpdatedMs", meta.lastUpdatedMs)
        json.put("contentHash", meta.contentHash)
        metaFile(context, name).writeText(json.toString())
    }

    private fun sha256(content: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun sanitize(name: String) = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
