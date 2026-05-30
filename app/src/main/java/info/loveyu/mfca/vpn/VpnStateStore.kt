package info.loveyu.mfca.vpn

import android.content.Context
import info.loveyu.mfca.config.VpnAccessControlMode
import org.json.JSONArray

class VpnStateStore(context: Context) {
    private val preferences = context.getSharedPreferences("vpn_state_store", Context.MODE_PRIVATE)

    fun isGlobalEnabled(defaultValue: Boolean = false): Boolean {
        return preferences.getBoolean(KEY_GLOBAL_ENABLED, defaultValue)
    }

    fun setGlobalEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_GLOBAL_ENABLED, enabled).apply()
    }

    fun getSelectionHistory(): List<String> {
        return runCatching {
            val raw = preferences.getString(KEY_SELECTION_HISTORY, "[]") ?: "[]"
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index)
                    if (value.isNotBlank()) {
                        add(value)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun pushSelection(name: String) {
        val values = LinkedHashSet<String>()
        values.add(name)
        getSelectionHistory().forEach { existing ->
            if (existing != name) {
                values.add(existing)
            }
        }
        preferences.edit().putString(KEY_SELECTION_HISTORY, JSONArray(values.toList()).toString()).apply()
    }

    fun getAccessControlMode(candidateName: String, defaultValue: VpnAccessControlMode): VpnAccessControlMode {
        val value = preferences.getString(modeKey(candidateName), null) ?: return defaultValue
        return when (value) {
            VpnAccessControlMode.include.name -> VpnAccessControlMode.include
            VpnAccessControlMode.exclude.name -> VpnAccessControlMode.exclude
            else -> VpnAccessControlMode.acceptAll
        }
    }

    fun getPackages(candidateName: String, defaultValue: List<String>): List<String> {
        val raw = preferences.getString(packagesKey(candidateName), null) ?: return defaultValue
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index)
                    if (value.isNotBlank()) {
                        add(value)
                    }
                }
            }
        }.getOrDefault(defaultValue)
    }

    fun setAccessControl(candidateName: String, mode: VpnAccessControlMode, packages: List<String>) {
        preferences.edit()
            .putString(modeKey(candidateName), mode.name)
            .putString(packagesKey(candidateName), JSONArray(packages.distinct()).toString())
            .apply()
    }

    companion object {
        private const val KEY_GLOBAL_ENABLED = "global_enabled"
        private const val KEY_SELECTION_HISTORY = "selection_history"

        private fun modeKey(candidateName: String): String = "mode_${sanitize(candidateName)}"

        private fun packagesKey(candidateName: String): String = "packages_${sanitize(candidateName)}"

        private fun sanitize(value: String): String = value.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
