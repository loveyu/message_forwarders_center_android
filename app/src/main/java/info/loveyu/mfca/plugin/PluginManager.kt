package info.loveyu.mfca.plugin

import android.content.Context
import android.os.Build
import info.loveyu.mfca.util.LogManager
import java.io.File
import java.io.InputStream
import java.net.URL

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

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun pluginDir(context: Context, pluginName: String): File {
        return File(context.filesDir, "plugins/$pluginName/$deviceAbi")
    }
}
