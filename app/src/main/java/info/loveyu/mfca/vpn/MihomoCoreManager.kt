package info.loveyu.mfca.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.system.Os
import info.loveyu.mfca.util.LogManager
import java.io.File

object MihomoCoreManager {
    private const val PLUGIN_PACKAGE = "info.loveyu.m2m"

    fun inspectCore(context: Context): VpnCoreState {
        val plugin = resolvePlugin(context) ?: return VpnCoreState(isReady = false)
        return VpnCoreState(
            isReady = plugin.lib.exists(),
            path = coreSymlinkPath(context).absolutePath.takeIf { plugin.lib.exists() },
            pluginVersion = plugin.version,
        )
    }

    fun ensureCore(context: Context): Result<File> = runCatching {
        val plugin =
            resolvePlugin(context) ?: error("未找到 Mihomo 核心插件，请先安装 $PLUGIN_PACKAGE")
        require(plugin.lib.exists()) { "插件核心文件不存在: ${plugin.lib.absolutePath}" }
        val symlink = coreSymlinkPath(context)
        symlink.parentFile?.mkdirs()
        symlink.delete()
        Os.symlink(plugin.lib.absolutePath, symlink.absolutePath)
        LogManager.logInfo(
            "VPN",
            "Using plugin mihomo (${plugin.version}): ${symlink.absolutePath}",
        )
        symlink
    }

    private data class PluginInfo(val lib: File, val version: String?)

    private fun resolvePlugin(context: Context): PluginInfo? =
        try {
            val appInfo = context.packageManager.getApplicationInfo(PLUGIN_PACKAGE, 0)
            val pkgInfo = context.packageManager.getPackageInfo(PLUGIN_PACKAGE, 0)
            PluginInfo(
                lib = File(appInfo.nativeLibraryDir, "libmihomo.so"),
                version = pkgInfo.versionName,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun coreSymlinkPath(context: Context) = File(context.filesDir, "vpn/core/mihomo")
}
