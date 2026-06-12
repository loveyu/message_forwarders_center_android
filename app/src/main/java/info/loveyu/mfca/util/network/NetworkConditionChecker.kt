package info.loveyu.mfca.util.network

import android.content.Context
import info.loveyu.mfca.util.LogManager
import java.net.URLDecoder

internal data class ConditionResult(val matched: Boolean, val reason: String?)

internal object NetworkConditionChecker {

    fun checkCondition(
        context: Context,
        condition: String,
        getSnapshot: (Context, Boolean) -> NetworkChecker.NetworkSnapshot,
        forceRefresh: Boolean = false,
    ): Boolean {
        val params = parseCondition(condition)
        val snapshot = getSnapshot(context, forceRefresh)

        params["network"]?.let { network ->
            if (!checkNetworkType(snapshot, network)) return false
        }

        params["ipRanges"]?.let { ranges ->
            if (!checkIpRange(snapshot, ranges)) return false
        }

        params["ssid"]?.let { ssid ->
            if (!checkWifiSsid(snapshot, ssid)) return false
        }

        params["bssid"]?.let { bssid ->
            if (!checkWifiBssid(snapshot, bssid)) return false
        }

        return true
    }

    fun checkConditionWithReason(
        context: Context,
        condition: String,
        getSnapshot: (Context, Boolean) -> NetworkChecker.NetworkSnapshot,
        forceRefresh: Boolean = false,
    ): ConditionResult {
        val params = parseCondition(condition)
        val snapshot = getSnapshot(context, forceRefresh)

        params["network"]?.let { network ->
            if (!checkNetworkType(snapshot, network)) {
                val currentType = snapshot.networkType ?: "none"
                return ConditionResult(false, "Network type mismatch: required=$network, current=$currentType")
            }
        }

        params["ipRanges"]?.let { ranges ->
            if (!checkIpRange(snapshot, ranges)) {
                val currentIp = snapshot.ipAddress ?: "unknown"
                return ConditionResult(false, "IP not in range: ranges=$ranges, current=$currentIp")
            }
        }

        params["ssid"]?.let { ssid ->
            if (!checkWifiSsid(snapshot, ssid)) {
                val currentSsid = snapshot.ssid ?: "unknown"
                return ConditionResult(false, "SSID mismatch: required=$ssid, current=$currentSsid")
            }
        }

        params["bssid"]?.let { bssid ->
            if (!checkWifiBssid(snapshot, bssid)) {
                val currentBssid = snapshot.bssid ?: "unknown"
                return ConditionResult(false, "BSSID mismatch: required=$bssid, current=$currentBssid")
            }
        }

        return ConditionResult(true, null)
    }

    private fun parseCondition(condition: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pairs = condition.split("&")
        for (pair in pairs) {
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                val key = URLDecoder.decode(kv[0].trim().replace("+", "%2B"), "UTF-8")
                val value = URLDecoder.decode(kv[1].trim().replace("+", "%2B"), "UTF-8")
                result[key] = value
            }
        }
        return result
    }

    private fun checkNetworkType(snapshot: NetworkChecker.NetworkSnapshot, type: String): Boolean {
        val types = type.lowercase().split(",").map { it.trim() }
        if (types.contains("any")) return true
        val currentType = snapshot.networkType ?: return false
        return types.any { t ->
            when (t) {
                "wifi" -> currentType == "wifi"
                "mobile" -> currentType == "mobile"
                "ethernet" -> currentType == "ethernet"
                else -> true
            }
        }
    }

    private fun checkIpRange(snapshot: NetworkChecker.NetworkSnapshot, ipRanges: String): Boolean {
        val currentIp = snapshot.ipAddress ?: return false
        val ranges = ipRanges.split(",").map { it.trim() }
        return ranges.any { range ->
            NetworkChecker.isIpInRange(currentIp, range)
        }
    }

    private fun checkWifiSsid(snapshot: NetworkChecker.NetworkSnapshot, ssidPattern: String): Boolean {
        if (snapshot.networkType != "wifi") return false
        val currentSsid = snapshot.ssid ?: ""
        if (currentSsid == "<unknown ssid>" || currentSsid.isEmpty()) {
            LogManager.logDebug("NETWORK", "SSID unavailable, treating SSID check as not matched")
            return false
        }
        val patterns = ssidPattern.split(",").map { it.trim() }
        return patterns.any { pattern ->
            if (pattern.startsWith("~")) {
                Regex(pattern.removePrefix("~")).matches(currentSsid)
            } else {
                currentSsid == pattern
            }
        }
    }

    private fun checkWifiBssid(snapshot: NetworkChecker.NetworkSnapshot, bssidPattern: String): Boolean {
        if (snapshot.networkType != "wifi") return false
        val currentBssid = snapshot.bssid
        if (currentBssid == null || currentBssid == "02:00:00:00:00:00" || currentBssid.isBlank()) {
            LogManager.logDebug("NETWORK", "BSSID unavailable, treating BSSID check as not matched")
            return false
        }
        val bssids = bssidPattern.split(",").map { it.trim() }
        return bssids.any { it == currentBssid }
    }
}
