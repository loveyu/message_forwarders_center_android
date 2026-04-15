package info.loveyu.mfca.util

import android.content.Context
import android.content.SharedPreferences
import info.loveyu.mfca.constants.ApiConstants
import org.json.JSONObject

class Preferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mfca_prefs", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt("port", ApiConstants.DEFAULT_PORT)
        set(value) = prefs.edit().putInt("port", value).apply()

    var forwardTarget: String
        get() = prefs.getString("forward_target", "") ?: ""
        set(value) = prefs.edit().putString("forward_target", value).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean("auto_start", false)
        set(value) = prefs.edit().putBoolean("auto_start", value).apply()

    var receivingEnabled: Boolean
        get() = prefs.getBoolean("receiving_enabled", true)
        set(value) = prefs.edit().putBoolean("receiving_enabled", value).apply()

    var forwardingEnabled: Boolean
        get() = prefs.getBoolean("forwarding_enabled", true)
        set(value) = prefs.edit().putBoolean("forwarding_enabled", value).apply()

    var configFilePath: String
        get() = prefs.getString("config_file_path", "") ?: ""
        set(value) = prefs.edit().putString("config_file_path", value).apply()

    var logLevel: String
        get() = prefs.getString("log_level", "INFO") ?: "INFO"
        set(value) = prefs.edit().putString("log_level", value).apply()

    var logToFile: Boolean
        get() = prefs.getBoolean("log_to_file", false)
        set(value) = prefs.edit().putBoolean("log_to_file", value).apply()

    var logToLogcatAll: Boolean
        get() = prefs.getBoolean("log_to_logcat_all", false)
        set(value) = prefs.edit().putBoolean("log_to_logcat_all", value).apply()

    var maxLogLines: Int
        get() = prefs.getInt("max_log_lines", 1000)
        set(value) = prefs.edit().putInt("max_log_lines", value).apply()

    var showTabLabel: Boolean
        get() = prefs.getBoolean("show_tab_label", true)
        set(value) = prefs.edit().putBoolean("show_tab_label", value).apply()

    fun saveFullConfig(configJson: String) {
        prefs.edit().putString("full_config", configJson).apply()
    }

    fun loadFullConfig(): String? {
        return prefs.getString("full_config", null)
    }

    fun hasConfig(): Boolean {
        return prefs.contains("full_config") && prefs.getString("full_config", null)?.isNotBlank() == true
    }

    fun loadConfigFromJson(jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)
            port = json.optInt("port", port)
            forwardTarget = json.optString("forwardTarget", forwardTarget)
            autoStart = json.optBoolean("autoStart", autoStart)
            receivingEnabled = json.optBoolean("receivingEnabled", receivingEnabled)
            forwardingEnabled = json.optBoolean("forwardingEnabled", forwardingEnabled)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun toJsonString(): String {
        return JSONObject().apply {
            put("port", port)
            put("forwardTarget", forwardTarget)
            put("autoStart", autoStart)
            put("receivingEnabled", receivingEnabled)
            put("forwardingEnabled", forwardingEnabled)
        }.toString()
    }
}
