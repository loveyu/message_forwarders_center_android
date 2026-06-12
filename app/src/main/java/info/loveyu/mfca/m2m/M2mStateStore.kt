package info.loveyu.mfca.m2m

import android.content.Context
import info.loveyu.mfca.config.models.M2mAccessControlMode
import org.json.JSONArray
import java.util.UUID

class M2mStateStore(context: Context) {
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

    fun getAccessControlMode(candidateName: String, defaultValue: M2mAccessControlMode): M2mAccessControlMode {
        val value = preferences.getString(modeKey(candidateName), null) ?: return defaultValue
        return when (value) {
            M2mAccessControlMode.include.name -> M2mAccessControlMode.include
            M2mAccessControlMode.exclude.name -> M2mAccessControlMode.exclude
            else -> M2mAccessControlMode.acceptAll
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

    fun setAccessControl(candidateName: String, mode: M2mAccessControlMode, packages: List<String>) {
        preferences.edit()
            .putString(modeKey(candidateName), mode.name)
            .putString(packagesKey(candidateName), JSONArray(packages.distinct()).toString())
            .apply()
    }

    fun getLocalPort(candidateName: String): Int? {
        val value = preferences.getInt(portKey(candidateName), -1)
        return if (value < 0) null else value
    }

    fun setLocalPort(candidateName: String, port: Int?) {
        val editor = preferences.edit()
        if (port == null) editor.remove(portKey(candidateName)) else editor.putInt(portKey(candidateName), port)
        editor.apply()
    }

    fun getRuleMode(candidateName: String): M2mRuleMode? {
        val raw = preferences.getString(ruleModeKey(candidateName), null) ?: return null
        return M2mRuleMode.entries.firstOrNull { it.name == raw }
    }

    fun setRuleMode(candidateName: String, mode: M2mRuleMode?) {
        val editor = preferences.edit()
        if (mode == null) editor.remove(ruleModeKey(candidateName)) else editor.putString(ruleModeKey(candidateName), mode.name)
        editor.apply()
    }

    fun getLogLevel(candidateName: String): M2mLogLevel? {
        val raw = preferences.getString(logLevelKey(candidateName), null) ?: return null
        return M2mLogLevel.entries.firstOrNull { it.name == raw }
    }

    fun setLogLevel(candidateName: String, level: M2mLogLevel?) {
        val editor = preferences.edit()
        if (level == null) editor.remove(logLevelKey(candidateName)) else editor.putString(logLevelKey(candidateName), level.name)
        editor.apply()
    }

    fun getShowSystemApps(defaultValue: Boolean = false): Boolean {
        return preferences.getBoolean(KEY_SHOW_SYSTEM_APPS, defaultValue)
    }

    fun setShowSystemApps(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_SYSTEM_APPS, enabled).apply()
    }

    fun getAppSortMode(): String? {
        return preferences.getString(KEY_APP_SORT_MODE, null)
    }

    fun setAppSortMode(mode: String?) {
        val editor = preferences.edit()
        if (mode == null) editor.remove(KEY_APP_SORT_MODE) else editor.putString(KEY_APP_SORT_MODE, mode)
        editor.apply()
    }

    fun getDownloadProxy(): String? = preferences.getString(KEY_DOWNLOAD_PROXY, null)

    fun setDownloadProxy(proxy: String?) {
        val editor = preferences.edit()
        if (proxy == null) editor.remove(KEY_DOWNLOAD_PROXY) else editor.putString(KEY_DOWNLOAD_PROXY, proxy)
        editor.apply()
    }

    fun getGeoUrlOverride(type: String): String? = preferences.getString(geoUrlKey(type), null)

    fun setGeoUrlOverride(type: String, url: String?) {
        val editor = preferences.edit()
        if (url == null) editor.remove(geoUrlKey(type)) else editor.putString(geoUrlKey(type), url)
        editor.apply()
    }

    fun getOrCreateApiSecret(): String {
        var secret = preferences.getString(KEY_API_SECRET, null)
        if (secret == null) {
            secret = UUID.randomUUID().toString().replace("-", "")
            preferences.edit().putString(KEY_API_SECRET, secret).apply()
        }
        return secret
    }

    fun getUdpRelay(candidateName: String, defaultValue: Boolean = false): Boolean {
        return preferences.getBoolean(udpRelayKey(candidateName), defaultValue)
    }

    fun setUdpRelay(candidateName: String, enabled: Boolean?) {
        val editor = preferences.edit()
        if (enabled == null) editor.remove(udpRelayKey(candidateName)) else editor.putBoolean(udpRelayKey(candidateName), enabled)
        editor.apply()
    }

    fun getIpv6(candidateName: String, defaultValue: Boolean = false): Boolean {
        return preferences.getBoolean(ipv6Key(candidateName), defaultValue)
    }

    fun setIpv6(candidateName: String, enabled: Boolean?) {
        val editor = preferences.edit()
        if (enabled == null) editor.remove(ipv6Key(candidateName)) else editor.putBoolean(ipv6Key(candidateName), enabled)
        editor.apply()
    }

    fun getDnsHijack(candidateName: String, defaultValue: Boolean = true): Boolean {
        return preferences.getBoolean(dnsHijackKey(candidateName), defaultValue)
    }

    fun setDnsHijack(candidateName: String, enabled: Boolean?) {
        val editor = preferences.edit()
        if (enabled == null) editor.remove(dnsHijackKey(candidateName)) else editor.putBoolean(dnsHijackKey(candidateName), enabled)
        editor.apply()
    }

    companion object {
        private const val KEY_GLOBAL_ENABLED = "global_enabled"
        private const val KEY_SELECTION_HISTORY = "selection_history"
        private const val KEY_SHOW_SYSTEM_APPS = "show_system_apps"
        private const val KEY_APP_SORT_MODE = "app_sort_mode"
        private const val KEY_DOWNLOAD_PROXY = "download_proxy"
        private const val KEY_API_SECRET = "api_secret"

        private fun modeKey(candidateName: String): String = "mode_${sanitize(candidateName)}"

        private fun packagesKey(candidateName: String): String = "packages_${sanitize(candidateName)}"

        private fun portKey(candidateName: String): String = "port_${sanitize(candidateName)}"

        private fun ruleModeKey(candidateName: String): String = "rule_mode_${sanitize(candidateName)}"

        private fun logLevelKey(candidateName: String): String = "log_level_${sanitize(candidateName)}"

        private fun udpRelayKey(candidateName: String): String = "udp_relay_${sanitize(candidateName)}"

        private fun ipv6Key(candidateName: String): String = "ipv6_${sanitize(candidateName)}"

        private fun dnsHijackKey(candidateName: String): String = "dns_hijack_${sanitize(candidateName)}"

        private fun geoUrlKey(type: String): String = "geo_url_${sanitize(type)}"

        private fun sanitize(value: String): String = value.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
