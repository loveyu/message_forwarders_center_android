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
                configUrl = data["configUrl"] as? String ?: "",
                isRunning = data["isRunning"] as? Boolean ?: false,
                isReceivingEnabled = data["isReceivingEnabled"] as? Boolean ?: true,
                isForwardingEnabled = data["isForwardingEnabled"] as? Boolean ?: true,
                autoStart = data["autoStart"] as? Boolean ?: false,
                appAutoStartOnBoot = data["appAutoStartOnBoot"] as? Boolean ?: false
            )
        } catch (e: Exception) {
            LogManager.log("APP_STATUS", "Failed to load status: ${e.message}")
            AppStatusConfig()
        }
    }

    fun saveStatus(context: Context, status: AppStatusConfig) {
        try {
            val saveYaml = Dump(DumpSettings.builder().build())

            val data = mapOf(
                "configUrl" to status.configUrl,
                "isRunning" to status.isRunning,
                "isReceivingEnabled" to status.isReceivingEnabled,
                "isForwardingEnabled" to status.isForwardingEnabled,
                "autoStart" to status.autoStart,
                "appAutoStartOnBoot" to status.appAutoStartOnBoot
            )

            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(baseDir, STATUS_FILE_NAME)
            file.writeText(saveYaml.dumpToString(data))
            LogManager.log("APP_STATUS", "Status saved")
        } catch (e: Exception) {
            LogManager.log("APP_STATUS", "Failed to save status: ${e.message}")
        }
    }
}
