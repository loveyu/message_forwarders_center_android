package info.loveyu.mfca.vpn

import android.content.Context
import android.system.Os
import info.loveyu.mfca.util.LogManager
import java.io.File

object MihomoCoreManager {
    fun inspectCore(context: Context): VpnCoreState {
        val lib = resolveLib(context)
        return VpnCoreState(
            isReady = lib.exists(),
            path = coreSymlinkPath(context).absolutePath.takeIf { lib.exists() },
        )
    }

    fun ensureCore(context: Context): Result<File> {
        return runCatching {
            val lib = resolveLib(context)
            require(lib.exists()) { "内置 mihomo 核心不存在: ${lib.absolutePath}" }
            val symlink = coreSymlinkPath(context)
            symlink.parentFile?.mkdirs()
            symlink.delete()
            Os.symlink(lib.absolutePath, symlink.absolutePath)
            LogManager.logInfo("VPN", "Using bundled mihomo: ${symlink.absolutePath}")
            symlink
        }
    }

    private fun resolveLib(context: Context) =
        File(context.applicationInfo.nativeLibraryDir, "libmihomo.so")

    private fun coreSymlinkPath(context: Context) =
        File(context.filesDir, "vpn/core/mihomo")
}
