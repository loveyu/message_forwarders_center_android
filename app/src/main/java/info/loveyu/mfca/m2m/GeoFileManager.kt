package info.loveyu.mfca.m2m

import android.content.Context
import info.loveyu.mfca.config.GeoConfig
import info.loveyu.mfca.util.HttpDownloader
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

enum class GeoFileType(
    val key: String,
    val fileName: String,
    val label: String,
) {
    GEOIP("geoip", "geoip.dat", "GeoIP"),
    GEOSITE("geosite", "geosite.dat", "GeoSite"),
    COUNTRY("country", "country.mmdb", "Country"),
    ASN("asn", "ASN.mmdb", "ASN");

    companion object {
        fun fromKey(key: String): GeoFileType? = entries.firstOrNull { it.key == key }

        val all: List<GeoFileType> = entries.toList()
    }
}

data class GeoCacheState(
    val type: GeoFileType,
    val isCached: Boolean,
    val fileSize: Long = 0,
    val lastModified: Long = 0,
)

object GeoFileManager {

    private const val TAG = "GeoFile"
    private const val GEO_DIR = "vpn/geo"

    fun getGeoDir(context: Context): File {
        val dir = File(context.filesDir, GEO_DIR)
        dir.mkdirs()
        return dir
    }

    fun getGeoFile(context: Context, type: GeoFileType): File {
        return File(getGeoDir(context), type.fileName)
    }

    fun getCacheState(context: Context, type: GeoFileType): GeoCacheState {
        val file = getGeoFile(context, type)
        return if (file.exists()) {
            GeoCacheState(type, true, file.length(), file.lastModified())
        } else {
            GeoCacheState(type, false)
        }
    }

    fun getAllCacheStates(context: Context): List<GeoCacheState> {
        return GeoFileType.all.map { getCacheState(context, it) }
    }

    fun deleteCache(context: Context, type: GeoFileType): Boolean {
        val file = getGeoFile(context, type)
        return if (file.exists()) file.delete() else true
    }

    fun deleteAllCache(context: Context): Map<GeoFileType, Boolean> {
        return GeoFileType.all.associateWith { deleteCache(context, it) }
    }

    suspend fun downloadFile(
        context: Context,
        type: GeoFileType,
        url: String,
        downloadProxy: String? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Result<GeoCacheState> = withContext(Dispatchers.IO) {
        val destFile = getGeoFile(context, type)
        val tmpFile = File(destFile.parentFile, "${type.fileName}.tmp")
        destFile.parentFile?.mkdirs()

        // Clean up stale temp file from previous interrupted download
        if (tmpFile.exists()) {
            tmpFile.delete()
        }

        runCatching {
            if (url.isBlank()) throw IOException("URL is empty")

            when {
                url.startsWith("http://") || url.startsWith("https://") -> {
                    val proxy = downloadProxy?.takeIf { it.isNotBlank() }?.let {
                        HttpDownloader.parseProxy(it)
                    }
                    val config = HttpDownloader.Config(
                        tag = TAG,
                        proxy = proxy,
                        connectTimeoutMs = 30_000L,
                        readTimeoutMs = 120_000L,
                        sslRetryCount = 2,
                        progressIntervalMs = 1000L,
                    )
                    val callback = onProgress?.let { callback ->
                        HttpDownloader.ProgressCallback { downloaded, total ->
                            callback(downloaded, total)
                        }
                    }
                    // Download to temp file first, then atomically rename
                    HttpDownloader.downloadToFile(url, tmpFile, config, callback)
                    if (!tmpFile.renameTo(destFile)) {
                        // Fallback: copy if rename fails (cross-filesystem)
                        tmpFile.copyTo(destFile, overwrite = true)
                        tmpFile.delete()
                    }
                    LogManager.logInfo(TAG, "Downloaded ${type.fileName} (${destFile.length()} bytes)")
                }

                url.startsWith("data://") -> {
                    val relativePath = url.removePrefix("data://")
                    val srcFile = File(context.filesDir, relativePath)
                    if (!srcFile.exists()) throw IOException("Source file not found: $url")
                    srcFile.copyTo(destFile, overwrite = true)
                    LogManager.logInfo(TAG, "Copied from data://: $relativePath")
                }

                url.startsWith("cache://") -> {
                    val relativePath = url.removePrefix("cache://")
                    val srcFile = File(context.cacheDir, relativePath)
                    if (!srcFile.exists()) throw IOException("Source file not found: $url")
                    srcFile.copyTo(destFile, overwrite = true)
                    LogManager.logInfo(TAG, "Copied from cache://: $relativePath")
                }

                url.startsWith("sdcard://") -> {
                    val relativePath = url.removePrefix("sdcard://")
                    val srcFile = if (relativePath.startsWith("/")) {
                        File(relativePath)
                    } else {
                        File(context.getExternalFilesDir(null), relativePath)
                    }
                    if (!srcFile.exists()) throw IOException("Source file not found: $url")
                    srcFile.copyTo(destFile, overwrite = true)
                    LogManager.logInfo(TAG, "Copied from sdcard://: $relativePath")
                }

                url.startsWith("file://") -> {
                    val absolutePath = url.removePrefix("file://")
                    val srcFile = File(absolutePath)
                    if (!srcFile.exists()) throw IOException("Source file not found: $url")
                    srcFile.copyTo(destFile, overwrite = true)
                    LogManager.logInfo(TAG, "Copied from file://: $absolutePath")
                }

                else -> throw IOException("Unsupported protocol in URL: $url")
            }

            onProgress?.invoke(destFile.length(), destFile.length())
            // Compute and cache MD5 hash for fast startup comparison
            computeAndSaveHash(destFile)
            getCacheState(context, type)
        }.onFailure {
            // Clean up temp file on failure
            runCatching { tmpFile.delete() }
        }
    }

    fun getGeoCacheDir(context: Context): String {
        return getGeoDir(context).absolutePath
    }

    fun getEffectiveUrl(
        type: GeoFileType,
        configUrl: String,
        stateStore: M2mStateStore,
    ): String {
        val override = stateStore.getGeoUrlOverride(type.key)
        if (!override.isNullOrBlank()) return override
        return configUrl
    }

    fun resolveUrl(type: GeoFileType, config: GeoConfig): String {
        return when (type) {
            GeoFileType.GEOIP -> config.geoip
            GeoFileType.GEOSITE -> config.geosite
            GeoFileType.COUNTRY -> config.country
            GeoFileType.ASN -> config.asn
        }
    }

    fun readSavedHash(file: File): String? {
        val hashFile = File(file.parentFile, "${file.name}.hash")
        return if (hashFile.exists()) hashFile.readText().trim() else null
    }

    private fun computeAndSaveHash(file: File) {
        runCatching {
            val md = MessageDigest.getInstance("MD5")
            val hashFile = File(file.parentFile, "${file.name}.hash")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            hashFile.writeText(md.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
