package info.loveyu.mfca.plugin

import android.content.Context
import android.os.Build
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.StoragePathResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
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
     * @return The installed [File] path.
     */
    fun installFromFile(context: Context, pluginName: String, source: File): File {
        val dest = getInstalledPath(context, pluginName)
        dest.parentFile?.mkdirs()
        source.copyTo(dest, overwrite = true)
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
     * This is a **blocking** call; run it from a background coroutine/thread.
     *
     * @param context     Android context.
     * @param pluginName  Plugin identifier.
     * @param url         Direct download URL of the .so file.
     * @return The installed [File] path.
     */
    fun installFromUrl(context: Context, pluginName: String, url: String): File {
        LogManager.logInfo(TAG, "Downloading plugin '$pluginName' from $url")
        val stream = URL(url).openStream()
        return installFromStream(context, pluginName, stream)
    }

    /**
     * Returns true if the plugin .so is already installed and non-empty.
     */
    fun isInstalled(context: Context, pluginName: String): Boolean {
        val f = getInstalledPath(context, pluginName)
        return f.exists() && f.length() > 0
    }

    /**
     * Delete the installed plugin .so (e.g. to force a fresh download).
     */
    fun uninstall(context: Context, pluginName: String) {
        getInstalledPath(context, pluginName).delete()
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

            val result = installFromFile(context, pluginName, soFile)

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
        val proxy = proxyAddress?.trim()?.takeIf { it.isNotBlank() }?.let { parseProxy(it) }
        val conn =
            (
                if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()
            ) as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true

        try {
            conn.inputStream.buffered().use { input ->
                dest.outputStream().buffered().use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseProxy(address: String): Proxy {
        val trimmed = address.trim()
        return when {
            trimmed.startsWith("socks5://", ignoreCase = true) ||
                trimmed.startsWith("socks4://", ignoreCase = true) -> {
                val parts = trimmed.substringAfter("://").split(":")
                Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress(parts[0], parts.getOrElse(1) { "1080" }.toInt()),
                )
            }
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> {
                val parts = trimmed.substringAfter("://").split(":")
                Proxy(
                    Proxy.Type.HTTP,
                    InetSocketAddress(parts[0], parts.getOrElse(1) { "8080" }.toInt()),
                )
            }
            else -> {
                val parts = trimmed.split(":")
                Proxy(
                    Proxy.Type.HTTP,
                    InetSocketAddress(parts[0], parts.getOrElse(1) { "8080" }.toInt()),
                )
            }
        }
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
