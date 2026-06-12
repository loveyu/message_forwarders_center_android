package info.loveyu.mfca.m2m.core

import android.content.Context
import info.loveyu.mfca.m2m.models.M2mCoreState
import info.loveyu.mfca.plugin.PluginManager
import info.loveyu.mfca.util.LogManager
import java.io.File

object M2mCoreManager {
    private const val PLUGIN_NAME = "m2m"

    fun inspectCore(context: Context): M2mCoreState {
        val plugin = PluginManager.getInstalledPath(context, PLUGIN_NAME)
        return M2mCoreState(
            isReady = plugin.exists() && plugin.length() > 0,
            path = plugin.absolutePath.takeIf { plugin.exists() && plugin.length() > 0 },
            pluginVersion = null,
        )
    }

    fun ensureCore(context: Context, pluginUrl: String? = null, proxyAddress: String? = null): Result<File> = runCatching {
        val plugin = resolveCore(context, pluginUrl, proxyAddress)
        require(plugin.exists() && plugin.length() > 0) { "m2m plugin file is empty or missing: ${plugin.absolutePath}" }
        LogManager.logInfo("VPN", "Using m2m plugin: ${plugin.absolutePath}")
        plugin
    }

    fun deleteCore(context: Context) {
        PluginManager.uninstall(context, PLUGIN_NAME)
        LogManager.logInfo("VPN", "Deleted m2m core plugin cache")
    }

    fun cancelDownload() {
        PluginManager.cancelInstall(PLUGIN_NAME)
    }

    fun downloadCore(context: Context, pluginUrl: String, proxyAddress: String? = null): Result<File> = runCatching {
        PluginManager.uninstall(context, PLUGIN_NAME)
        LogManager.logInfo("VPN", "Re-downloading m2m core from $pluginUrl")
        val plugin = PluginManager.installFromUrl(context, PLUGIN_NAME, pluginUrl, proxyAddress)
        require(plugin.exists() && plugin.length() > 0) { "Downloaded plugin file is empty or missing" }
        LogManager.logInfo("VPN", "Downloaded m2m plugin: ${plugin.absolutePath}")
        plugin
    }

    private fun resolveCore(context: Context, pluginUrl: String?, proxyAddress: String? = null): File {
        if (!pluginUrl.isNullOrBlank()) {
            if (PluginManager.isInstalledFrom(context, PLUGIN_NAME, pluginUrl)) {
                return PluginManager.getInstalledPath(context, PLUGIN_NAME)
            }
            // URL changed or not yet installed from this URL — re-download
            return PluginManager.installFromUrl(context, PLUGIN_NAME, pluginUrl, proxyAddress)
        }
        // No URL configured — use whatever is installed
        if (PluginManager.isInstalled(context, PLUGIN_NAME)) {
            return PluginManager.getInstalledPath(context, PLUGIN_NAME)
        }
        error(
            "libm2m_plugin.so is not installed. " +
                "Install it via PluginManager or set plugin.m2mCore in the config.",
        )
    }
}
