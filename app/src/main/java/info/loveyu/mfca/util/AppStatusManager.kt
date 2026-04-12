package info.loveyu.mfca.util

import android.content.Context
import info.loveyu.mfca.config.AppStatusConfig
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

object AppStatusManager {
    private const val STATUS_FILE_NAME = "app_status.yaml"

    private val yaml = Load(LoadSettings.builder().build())

    fun loadStatus(context: Context): AppStatusConfig {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(baseDir, STATUS_FILE_NAME)
        if (!file.exists()) {
            return AppStatusConfig()
        }

        return try {
            val data = yaml.loadFromString(file.readText()) as? Map<String, Any>
                ?: return AppStatusConfig()

            AppStatusConfig(
                version = data["version"] as? String ?: "1.0",
                configUrl = data["config_url"] as? String ?: "",
                isRunning = data["is_running"] as? Boolean ?: false,
                isReceivingEnabled = data["is_receiving_enabled"] as? Boolean ?: true,
                isForwardingEnabled = data["is_forwarding_enabled"] as? Boolean ?: true,
                autoStart = data["auto_start"] as? Boolean ?: false,
                appAutoStartOnBoot = data["app_auto_start_on_boot"] as? Boolean ?: false
            )
        } catch (e: Exception) {
            LogManager.appendLog("APP_STATUS", "Failed to load status: ${e.message}")
            AppStatusConfig()
        }
    }

    fun saveStatus(context: Context, status: AppStatusConfig) {
        try {
            val saveYaml = Dump(DumpSettings.builder().build())

            val data = mapOf(
                "version" to status.version,
                "config_url" to status.configUrl,
                "is_running" to status.isRunning,
                "is_receiving_enabled" to status.isReceivingEnabled,
                "is_forwarding_enabled" to status.isForwardingEnabled,
                "auto_start" to status.autoStart,
                "app_auto_start_on_boot" to status.appAutoStartOnBoot
            )

            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(baseDir, STATUS_FILE_NAME)
            file.writeText(saveYaml.dumpToString(data))
            LogManager.appendLog("APP_STATUS", "Status saved")
        } catch (e: Exception) {
            LogManager.appendLog("APP_STATUS", "Failed to save status: ${e.message}")
        }
    }
}
