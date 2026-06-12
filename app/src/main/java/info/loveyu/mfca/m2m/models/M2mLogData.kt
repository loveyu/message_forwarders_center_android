package info.loveyu.mfca.m2m.models

import android.content.Context
import java.io.File

data class LogLine(val text: String, val isStderr: Boolean)

enum class LogSource(val label: String) {
    M2M("m2m"),
    BRIDGE("Bridge"),
}

fun exportVpnDiagnostics(context: Context): String {
    val extDir = File(context.getExternalFilesDir(null), "vpn_debug").apply { mkdirs() }
    val cacheVpnDir = File(context.cacheDir, "vpn")
    val files = mutableListOf<Pair<File, String>>()

    val profilesDir = File(context.filesDir, "vpn/profiles")
    if (profilesDir.exists()) {
        profilesDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".runtime.yaml")) {
                files.add(f to "profiles/${f.name}")
            }
        }
    }

    if (cacheVpnDir.exists()) {
        cacheVpnDir.listFiles()?.forEach { candidateDir ->
            if (candidateDir.isDirectory) {
                File(candidateDir, "m2m.stdout.log").takeIf { it.exists() }?.let {
                    files.add(it to "${candidateDir.name}/m2m.stdout.log")
                }
                File(candidateDir, "m2m.stderr.log").takeIf { it.exists() }?.let {
                    files.add(it to "${candidateDir.name}/m2m.stderr.log")
                }
                val bridgeDir = File(candidateDir, "bridge")
                if (bridgeDir.exists()) {
                    File(bridgeDir, "bridge.stdout.log").takeIf { it.exists() }?.let {
                        files.add(it to "${candidateDir.name}/bridge/bridge.stdout.log")
                    }
                    File(bridgeDir, "bridge.stderr.log").takeIf { it.exists() }?.let {
                        files.add(it to "${candidateDir.name}/bridge/bridge.stderr.log")
                    }
                }
            }
        }
    }

    val configCacheDir = File(context.filesDir, "vpn/config_cache")
    if (configCacheDir.exists()) {
        configCacheDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".yaml")) {
                files.add(f to "config_cache/${f.name}")
            }
        }
    }

    val appLogsDir = File(context.cacheDir, "logs")
    if (appLogsDir.exists()) {
        appLogsDir.listFiles()?.forEach { f ->
            if (f.isFile) {
                files.add(f to "app_logs/${f.name}")
            }
        }
    }

    var count = 0
    files.forEach { (src, relPath) ->
        val target = File(extDir, relPath)
        target.parentFile?.mkdirs()
        src.copyTo(target, overwrite = true)
        count++
    }
    return "已导出 $count 个文件到 ${extDir.absolutePath}"
}
