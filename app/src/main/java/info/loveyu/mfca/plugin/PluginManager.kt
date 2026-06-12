package info.loveyu.mfca.plugin

import android.content.Context
import android.os.Build
import info.loveyu.mfca.util.http.HttpDownloader
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.StoragePathResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Manages plugin .so file installation into the app's private files directory.
 *
 * Plugins are stored at:
 *   <filesDir>/plugins/<pluginName>/<abi>/lib<pluginName>_plugin.so
 *
 * A plugin can be installed from:
 *   - A local [File] (e.g. user-provided via file picker)
 *   - A remote URL (downloaded over HTTPS)
 *   - An [InputStream] (e.g. from an asset or content URI)
 *
 * The installed path is always the canonical path returned by [getInstalledPath].
 */
object PluginManager {
    private const val TAG = "PluginManager"

    private val downloadLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * The preferred ABI for this device, chosen from [Build.SUPPORTED_ABIS].
     * Matches the subdirectory names used in the jniLibs zip.
     */
    val deviceAbi: String
        get() = Build.SUPPORTED_ABIS.firstOrNull { abi ->
            abi in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        } ?: "arm64-v8a"

    /**
     * Returns the canonical path where the plugin .so should be installed.
     * The file may not exist yet; call one of the install functions first.
     */
    fun getInstalledPath(context: Context, pluginName: String): File {
        return pluginDir(context, pluginName).resolve("lib${pluginName}_plugin.so")
    }

    /**
     * Install a plugin .so from a local [File].
     *
     * @param context     Android context.
     * @param pluginName  Plugin identifier (e.g. "udp2raw").
     * @param source      Source .so file.
     * @param sourceUrl   Optional URL to record as the source (used for cache invalidation).
     * @return The installed [File] path.
     */
    fun installFromFile(
        context: Context,
        pluginName: String,
        source: File,
        sourceUrl: String? = null,
    ): File {
        val dest = getInstalledPath(context, pluginName)
        dest.parentFile?.mkdirs()
        source.copyTo(dest, overwrite = true)
        sourceUrl?.let { sourceMarkerFile(context, pluginName).writeText(it) }
        LogManager.logInfo(TAG, "Installed plugin '$pluginName' from ${source.absolutePath}")
        return dest
    }

    /**
     * Install a plugin .so from an [InputStream].
     *
     * @param context     Android context.
     * @param pluginName  Plugin identifier.
     * @param stream      Source stream (closed after reading).
     * @return The installed [File] path.
     */
    fun installFromStream(context: Context, pluginName: String, stream: InputStream): File {
        val dest = getInstalledPath(context, pluginName)
        dest.parentFile?.mkdirs()
        stream.use { it.copyTo(dest.outputStream()) }
        LogManager.logInfo(TAG, "Installed plugin '$pluginName' from stream")
        return dest
    }

    /**
     * Download and install a plugin .so from a remote URL.
     *
     * Thread-safe: concurrent calls for the same [pluginName] will block and
     * return the same result without redundant downloads.
     *
     * Supports .gz / .gzip compressed files (auto-detected from URL extension).
     * Logs download progress every ~20 seconds.
     *
     * This is a **blocking** call; run it from a background coroutine/thread.
     *
     * @param context     Android context.
     * @param pluginName  Plugin identifier.
     * @param url         Direct download URL of the .so file (or .so.gz).
     * @return The installed [File] path.
     */
    fun installFromUrl(context: Context, pluginName: String, url: String, proxyAddress: String? = null): File {
        val lock = downloadLocks.computeIfAbsent(pluginName) { ReentrantLock() }
        lock.lock()
        try {
            // Double-check: another thread may have completed the download
            if (isInstalledFrom(context, pluginName, url)) {
                LogManager.logInfo(TAG, "Plugin '$pluginName' already installed by another thread")
                return getInstalledPath(context, pluginName)
            }

            LogManager.logInfo(TAG, "Downloading plugin '$pluginName' from $url")
            val proxy = proxyAddress?.trim()?.takeIf { it.isNotBlank() }?.let {
                HttpDownloader.parseProxy(it)
            }
            val tempFile = File(context.cacheDir, "plugin_download_${pluginName}_${System.currentTimeMillis()}")
            try {
                HttpDownloader.downloadToFile(
                    url,
                    tempFile,
                    HttpDownloader.Config(
                        connectTimeoutMs = 30_000L,
                        readTimeoutMs = 30_000L,
                        proxy = proxy,
                        tag = "plugin_$pluginName",
                    ),
                    progressCallback = HttpDownloader.ProgressCallback { downloaded, total ->
                        logProgress(pluginName, downloaded, total)
                    },
                )

                val isGz = detectFormat(url) == "gz"
                val dest = getInstalledPath(context, pluginName)
                dest.parentFile?.mkdirs()
                if (isGz) {
                    GZIPInputStream(tempFile.inputStream().buffered()).use { gis ->
                        dest.outputStream().buffered().use { out -> gis.copyTo(out) }
                    }
                } else {
                    tempFile.copyTo(dest, overwrite = true)
                }

                val sizeKB = dest.length() / 1024
                LogManager.logInfo(TAG, "Installed plugin '$pluginName' from $url (${sizeKB}KB)")
            } finally {
                tempFile.delete()
            }

            sourceMarkerFile(context, pluginName).writeText(url)
            return getInstalledPath(context, pluginName)
        } finally {
            lock.unlock()
        }
    }

    fun cancelInstall(pluginName: String) {
        HttpDownloader.cancel("plugin_$pluginName")
    }

    private fun logProgress(pluginName: String, downloaded: Long, totalSize: Long) {
        val sizeKB = downloaded / 1024
        if (totalSize > 0) {
            val pct = downloaded * 100 / totalSize
            LogManager.logInfo(TAG, "Downloading '$pluginName': ${sizeKB}KB ($pct%)")
        } else {
            LogManager.logInfo(TAG, "Downloading '$pluginName': ${sizeKB}KB")
        }
    }

    /**
     * Returns true if the plugin .so is already installed and non-empty.
     */
    fun isInstalled(context: Context, pluginName: String): Boolean {
        val f = getInstalledPath(context, pluginName)
        return f.exists() && f.length() > 0
    }

    /**
     * Returns true if the plugin is installed **and** the stored source URL
     * matches [url]. Returns false when the URL differs or has never been recorded.
     */
    fun isInstalledFrom(context: Context, pluginName: String, url: String): Boolean {
        if (!isInstalled(context, pluginName)) return false
        val marker = sourceMarkerFile(context, pluginName)
        return marker.exists() && marker.readText().trim() == url.trim()
    }

    private fun sourceMarkerFile(context: Context, pluginName: String): File {
        return pluginDir(context, pluginName).resolve(".source_url")
    }

    /**
     * Delete the installed plugin .so (e.g. to force a fresh download).
     */
    fun uninstall(context: Context, pluginName: String) {
        getInstalledPath(context, pluginName).delete()
        sourceMarkerFile(context, pluginName).delete()
        LogManager.logInfo(TAG, "Uninstalled plugin '$pluginName'")
    }

    /**
     * Download and install a plugin from a URL or local protocol path.
     *
     * Supports:
     * - Remote URLs (http://, https://)
     * - Local protocol paths (data://, sdcard://, cache://, file://)
     * - Archive formats: .zip, .gz, .gzip (extracts first .so file)
     * - Raw .so files
     * - Optional HTTP/SOCKS proxy for remote downloads
     *
     * This is a **suspend** function that respects coroutine cancellation.
     */
    suspend fun installPlugin(
        context: Context,
        pluginName: String,
        url: String,
        proxyAddress: String? = null,
    ): File =
        withContext(Dispatchers.IO) {
            val isLocal =
                url.startsWith("data://") || url.startsWith("sdcard://") ||
                    url.startsWith("cache://") || url.startsWith("file://")
            val format = detectFormat(url)

            val sourceFile =
                if (isLocal) {
                    StoragePathResolver.resolveFile(context, url, allowRawPath = true)
                } else {
                    val tempFile =
                        File(context.cacheDir, "plugin_download_${System.currentTimeMillis()}")
                    try {
                        downloadToTempFile(url, proxyAddress, tempFile)
                    } catch (e: Exception) {
                        tempFile.delete()
                        throw e
                    }
                    tempFile
                }

            ensureActive()

            val soFile =
                when (format) {
                    "so" -> sourceFile
                    "zip" -> extractSoFromZip(sourceFile)
                    "gz" -> extractSoFromGzip(sourceFile)
                    else -> throw IllegalArgumentException("Unsupported file format: $format")
                }

            ensureActive()

            val result = installFromFile(context, pluginName, soFile, sourceUrl = url)

            if (!isLocal) sourceFile.delete()
            if (soFile != sourceFile) soFile.delete()

            result
        }

    private fun detectFormat(url: String): String {
        val path = url.substringBefore("?").substringAfterLast("/")
        return when {
            path.endsWith(".zip", ignoreCase = true) -> "zip"
            path.endsWith(".gz", ignoreCase = true) -> "gz"
            path.endsWith(".gzip", ignoreCase = true) -> "gz"
            path.endsWith(".so", ignoreCase = true) -> "so"
            else -> "so"
        }
    }

    private suspend fun downloadToTempFile(url: String, proxyAddress: String?, dest: File) {
        val proxy = proxyAddress?.trim()?.takeIf { it.isNotBlank() }?.let {
            HttpDownloader.parseProxy(it)
        }
        HttpDownloader.downloadToFileSuspend(
            url,
            dest,
            HttpDownloader.Config(
                connectTimeoutMs = 30_000L,
                readTimeoutMs = 30_000L,
                proxy = proxy,
                tag = "plugin_download",
            ),
        )
    }

    private fun extractSoFromZip(zipFile: File): File {
        val tempDir = File(zipFile.parentFile, "extract_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".so")) {
                    val outFile = File(tempDir, File(entry.name).name)
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                    return outFile
                }
                entry = zis.nextEntry
            }
        }
        tempDir.deleteRecursively()
        throw FileNotFoundException("ZIP archive does not contain a .so file")
    }

    private fun extractSoFromGzip(gzFile: File): File {
        val outFile = File(gzFile.parentFile, "extracted_${System.currentTimeMillis()}.so")
        GZIPInputStream(gzFile.inputStream().buffered()).use { gis ->
            outFile.outputStream().use { out -> gis.copyTo(out) }
        }
        return outFile
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun pluginDir(context: Context, pluginName: String): File {
        return File(context.filesDir, "plugins/$pluginName/$deviceAbi")
    }
}
