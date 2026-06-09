package info.loveyu.mfca.vpn

import android.content.Context
import info.loveyu.mfca.plugin.PluginManager
import info.loveyu.mfca.util.LogManager
import java.io.File

object MihomoCoreManager {
    private const val PLUGIN_NAME = "mihomo"

    fun inspectCore(context: Context): VpnCoreState {
        val plugin = PluginManager.getInstalledPath(context, PLUGIN_NAME)
        return VpnCoreState(
            isReady = plugin.exists() && plugin.length() > 0,
            path = plugin.absolutePath.takeIf { plugin.exists() && plugin.length() > 0 },
            pluginVersion = null,
        )
    }

    fun ensureCore(context: Context, pluginUrl: String? = null): Result<File> = runCatching {
        val plugin =
            if (PluginManager.isInstalled(context, PLUGIN_NAME)) {
                PluginManager.getInstalledPath(context, PLUGIN_NAME)
            } else if (!pluginUrl.isNullOrBlank()) {
                PluginManager.installFromUrl(context, PLUGIN_NAME, pluginUrl)
            } else {
                error(
                    "libmihomo_plugin.so is not installed. " +
                        "Install it via PluginManager or set plugin.m2mCore in the config.",
                )
            }
        require(plugin.exists() && plugin.length() > 0) { "Mihomo plugin file is empty or missing: ${plugin.absolutePath}" }
        LogManager.logInfo(
            "VPN",
            "Using mihomo plugin: ${plugin.absolutePath}",
        )
        plugin
    }
}
