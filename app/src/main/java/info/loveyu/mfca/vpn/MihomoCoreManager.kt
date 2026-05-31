package info.loveyu.mfca.vpn

import android.content.Context
import info.loveyu.mfca.util.LogManager
import java.io.File

object MihomoCoreManager {
    fun inspectCore(context: Context): VpnCoreState {
        val lib = resolveLib(context)
        return VpnCoreState(isReady = lib.exists(), path = lib.absolutePath.takeIf { lib.exists() })
    }

    fun ensureCore(context: Context): Result<File> {
        return runCatching {
            val lib = resolveLib(context)
            require(lib.exists()) { "内置 mihomo 核心不存在: ${lib.absolutePath}" }
            LogManager.logInfo("VPN", "Using bundled mihomo: ${lib.absolutePath}")
            lib
        }
    }

    private fun resolveLib(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libmihomo.so")
}
