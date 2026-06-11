package info.loveyu.mfca.m2m

import android.content.Context
import info.loveyu.mfca.config.M2mInputConfig
import info.loveyu.mfca.util.HttpDownloader
import info.loveyu.mfca.util.LogManager
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object M2mConfigCacheManager {

    fun cancelDownload() {
        HttpDownloader.cancel("vpn_config")
    }

    private data class CacheMeta(
        val lastUpdatedMs: Long,
        val contentHash: String,
    )

    fun inspect(context: Context, config: M2mInputConfig): M2mConfigCacheState {
        val source = sourceFile(context, config.name)
        val meta = loadMeta(context, config.name)
        val nextRefreshMs =
            if (config.refreshIntervalMs > 0 && meta != null) {
                meta.lastUpdatedMs + config.refreshIntervalMs
            } else {
                null
            }
        return M2mConfigCacheState(
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
    fun downloadConfig(context: Context, config: M2mInputConfig): Result<Boolean> {
        return runCatching {
            require(
                config.configUrl.startsWith("http://") ||
                    config.configUrl.startsWith("https://"),
            ) {
                "configUrl 仅支持 http/https"
            }
            cacheDir(context).mkdirs()
            val content = HttpDownloader.downloadString(
                config.configUrl,
                HttpDownloader.Config(
                    connectTimeoutMs = 15_000L,
                    readTimeoutMs = 30_000L,
                    sslRetryCount = 2,
                    tag = "vpn_config",
                    userAgent = "FlowGate-Android",
                ),
            )
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
