package info.loveyu.mfca.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder

/**
 * 网络状态检查器
 *
 * when/deny 条件格式 (URI query string):
 *   network=wifi|mobile|ethernet|any  - 网络类型，逗号分隔支持多值(OR)，如 network=wifi,mobile
 *   ssid=MyWiFi,~MyWiFi-.*          - WiFi名称，支持正则(前缀~)
 *   bssid=AA:BB:CC:DD:EE:FF          - WiFi MAC地址
 *   ipRanges=192.168.1.0/24,10.0.0.10 - IP段，逗号分隔
 *
 * 示例:
 *   when: network=wifi
 *   when: network=wifi&ssid=MyHomeWiFi
 *   when: network=wifi&ipRanges=192.168.1.0/24
 *   deny: network=mobile
 */
object NetworkChecker {

    /**
     * 网络状态快照，缓存系统服务查询结果，避免每次 shouldEnable 都查询。
     * 纯事件驱动缓存：仅在 invalidateCache() 调用时失效（网络变更回调触发），
     * 网络稳定期间零系统服务查询。
     */
    private data class NetworkSnapshot(
        val networkType: String?,
        val ssid: String?,
        val bssid: String?,
        val ipAddress: String?
    )

    @Volatile
    private var cachedSnapshot: NetworkSnapshot? = null

    /**
     * 获取网络状态快照（纯事件驱动，无 TTL 过期）
     */
    private fun getSnapshot(context: Context): NetworkSnapshot {
        cachedSnapshot?.let { return it }

        val snapshot = querySnapshot(context)
        cachedSnapshot = snapshot
        return snapshot
    }

    /**
     * 强制刷新快照（网络变更事件时调用）
     */
    fun invalidateCache() {
        cachedSnapshot = null
    }

    private fun querySnapshot(context: Context): NetworkSnapshot {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        val networkType: String? = if (capabilities != null) {
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "unknown"
            }
        } else null

        var ssid: String? = null
        var bssid: String? = null
        var ipAddress: String? = null

        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            try {
                val wifiInfo = wifiManager.connectionInfo
                ssid = wifiInfo?.ssid?.removeSurrounding("\"")
                bssid = wifiInfo?.bssid
                val ip = wifiInfo?.ipAddress
                if (ip != null && ip != 0) {
                    ipAddress = formatIpAddress(ip)
                }
            } catch (_: SecurityException) {
                // permission denied
            }
        }

        // 非 WiFi 或 WiFi 未获取到 IP 时，从网络接口获取
        if (ipAddress == null) {
            ipAddress = queryIpFromInterfaces()
        }

        return NetworkSnapshot(networkType, ssid, bssid, ipAddress)
    }

    private fun queryIpFromInterfaces(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * 检查链接是否应该启用
     */
    fun shouldEnable(context: Context, whenCondition: String?, denyCondition: String?): Boolean {
        // First check deny conditions - if any matches, deny immediately
        if (denyCondition != null && checkCondition(context, denyCondition)) {
            return false
        }

        // Then check when conditions - if specified and doesn't match, deny
        if (whenCondition != null && !checkCondition(context, whenCondition)) {
            return false
        }

        return true
    }

    /**
     * 获取链接是否应该启用及原因
     */
    fun getEnableReason(context: Context, whenCondition: String?, denyCondition: String?): EnableResult {
        // First check deny conditions
        if (denyCondition != null) {
            val denyResult = checkConditionWithReason(context, denyCondition)
            if (denyResult.matched) {
                return EnableResult(enabled = false, reason = "Denied by condition: $denyCondition")
            }
        }

        // Then check when conditions
        if (whenCondition != null) {
            val whenResult = checkConditionWithReason(context, whenCondition)
            if (!whenResult.matched) {
                return EnableResult(enabled = false, reason = "Condition not met: $whenCondition")
            }
        }

        return EnableResult(enabled = true, reason = null)
    }

    data class EnableResult(val enabled: Boolean, val reason: String?)

    /**
     * 检查条件是否匹配（使用快照缓存）
     */
    private fun checkCondition(context: Context, condition: String): Boolean {
        val params = parseCondition(condition)
        val snapshot = getSnapshot(context)

        // Check network type
        params["network"]?.let { network ->
            if (!checkNetworkType(snapshot, network)) {
                return false
            }
        }

        // Check IP ranges
        params["ipRanges"]?.let { ranges ->
            if (!checkIpRange(snapshot, ranges)) {
                return false
            }
        }

        // Check WiFi SSID
        params["ssid"]?.let { ssid ->
            if (!checkWifiSsid(snapshot, ssid)) {
                return false
            }
        }

        // Check WiFi BSSID
        params["bssid"]?.let { bssid ->
            if (!checkWifiBssid(snapshot, bssid)) {
                return false
            }
        }

        return true
    }

    /**
     * 检查条件是否匹配，返回详细原因（使用快照缓存）
     */
    private fun checkConditionWithReason(context: Context, condition: String): ConditionResult {
        val params = parseCondition(condition)
        val snapshot = getSnapshot(context)

        // Check network type
        params["network"]?.let { network ->
            if (!checkNetworkType(snapshot, network)) {
                val currentType = snapshot.networkType ?: "none"
                return ConditionResult(false, "Network type mismatch: required=$network, current=$currentType")
            }
        }

        // Check IP ranges
        params["ipRanges"]?.let { ranges ->
            if (!checkIpRange(snapshot, ranges)) {
                val currentIp = snapshot.ipAddress ?: "unknown"
                return ConditionResult(false, "IP not in range: ranges=$ranges, current=$currentIp")
            }
        }

        // Check WiFi SSID
        params["ssid"]?.let { ssid ->
            if (!checkWifiSsid(snapshot, ssid)) {
                val currentSsid = snapshot.ssid ?: "unknown"
                return ConditionResult(false, "SSID mismatch: required=$ssid, current=$currentSsid")
            }
        }

        // Check WiFi BSSID
        params["bssid"]?.let { bssid ->
            if (!checkWifiBssid(snapshot, bssid)) {
                val currentBssid = snapshot.bssid ?: "unknown"
                return ConditionResult(false, "BSSID mismatch: required=$bssid, current=$currentBssid")
            }
        }

        return ConditionResult(true, null)
    }

    private data class ConditionResult(val matched: Boolean, val reason: String?)

    /**
     * 解析条件字符串为 key=value map
     * 格式 (URI query string): network=wifi&ssid=MyWiFi&ipRanges=192.168.1.0%2F24
     * 使用 & 分隔参数，值内的逗号不需要编码
     * 注意: + 号不会被当作空格处理，会作为字面字符保留
     */
    private fun parseCondition(condition: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pairs = condition.split("&")
        for (pair in pairs) {
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                // 先将 + 替换为 %2B，防止 URLDecoder 把 + 当作空格处理
                val key = URLDecoder.decode(kv[0].trim().replace("+", "%2B"), "UTF-8")
                val value = URLDecoder.decode(kv[1].trim().replace("+", "%2B"), "UTF-8")
                result[key] = value
            }
        }
        return result
    }

    /**
     * 检查网络类型，支持逗号分隔多值(OR逻辑)
     */
    private fun checkNetworkType(snapshot: NetworkSnapshot, type: String): Boolean {
        val types = type.lowercase().split(",").map { it.trim() }

        // 如果包含 "any"，直接返回 true（无网络时也通过）
        if (types.contains("any")) return true

        // 无网络时，所有类型都不匹配
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

    /**
     * 检查 IP 段
     */
    private fun checkIpRange(snapshot: NetworkSnapshot, ipRanges: String): Boolean {
        val currentIp = snapshot.ipAddress ?: return false
        val ranges = ipRanges.split(",").map { it.trim() }

        return ranges.any { range ->
            isIpInRange(currentIp, range)
        }
    }

    /**
     * 检查 WiFi SSID
     */
    private fun checkWifiSsid(snapshot: NetworkSnapshot, ssidPattern: String): Boolean {
        // 非 WiFi 时 SSID 不匹配
        if (snapshot.networkType != "wifi") return false

        val currentSsid = snapshot.ssid ?: ""

        // 后台时系统可能返回 <unknown ssid>，此时无法判断，跳过SSID检查以保持连接
        if (currentSsid == "<unknown ssid>" || currentSsid.isEmpty()) {
            LogManager.log("NETWORK", "SSID unavailable (app in background?), skipping SSID check")
            return true
        }

        // ssidPattern can be comma-separated list
        val patterns = ssidPattern.split(",").map { it.trim() }
        return patterns.any { pattern ->
            if (pattern.startsWith("~")) {
                // Regex pattern
                Regex(pattern.removePrefix("~")).matches(currentSsid)
            } else {
                currentSsid == pattern
            }
        }
    }

    /**
     * 检查 WiFi BSSID
     */
    private fun checkWifiBssid(snapshot: NetworkSnapshot, bssidPattern: String): Boolean {
        // 非 WiFi 时 BSSID 不匹配
        if (snapshot.networkType != "wifi") return false

        val currentBssid = snapshot.bssid ?: return true

        // 后台时系统可能返回 02:00:00:00:00:00，此时无法判断，跳过BSSID检查以保持连接
        if (currentBssid == "02:00:00:00:00:00") {
            LogManager.log("NETWORK", "BSSID unavailable (app in background?), skipping BSSID check")
            return true
        }

        // bssidPattern can be comma-separated list
        val bssids = bssidPattern.split(",").map { it.trim() }
        return bssids.any { it == currentBssid }
    }

    private fun formatIpAddress(ipAddress: Int): String {
        return "${ipAddress and 0xFF}.${ipAddress shr 8 and 0xFF}.${ipAddress shr 16 and 0xFF}.${ipAddress shr 24 and 0xFF}"
    }

    /**
     * 检查 IP 是否在 CIDR/精确地址段内
     */
    fun isIpInRange(ip: String, cidr: String): Boolean {
        return try {
            if (!cidr.contains("/")) {
                // No prefix, exact match
                return ip == cidr
            }

            val parts = cidr.split("/")
            val networkAddress = parts[0]
            val prefixLength = parts[1].toIntOrNull() ?: return ip == networkAddress

            val ipParts = ip.split(".").map { it.toIntOrNull() ?: 0 }
            val netParts = networkAddress.split(".").map { it.toIntOrNull() ?: 0 }

            if (ipParts.size != 4 || netParts.size != 4) return false

            val ipInt = (ipParts[0] shl 24) or (ipParts[1] shl 16) or (ipParts[2] shl 8) or ipParts[3]
            val netInt = (netParts[0] shl 24) or (netParts[1] shl 16) or (netParts[2] shl 8) or netParts[3]

            val mask = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))

            (ipInt and mask) == (netInt and mask)
        } catch (e: Exception) {
            LogManager.log("NETWORK", "IP range check error: ${e.message}")
            false
        }
    }

    /**
     * 获取当前网络条件与 when/deny 的匹配详情（直接查询，用于 UI 展示）
     */
    fun getMatchedConditions(context: Context, whenCondition: String?, denyCondition: String?): String {
        val sb = StringBuilder()
        val snapshot = getSnapshot(context)

        val type = snapshot.networkType ?: "none"
        val typeDisplay = when (type) {
            "wifi" -> "WiFi"
            "mobile" -> "Mobile"
            "ethernet" -> "Ethernet"
            "unknown" -> "Unknown"
            else -> type
        }
        sb.append("Network: $typeDisplay")

        if (type == "wifi") {
            val ssid = snapshot.ssid ?: "unknown"
            val bssid = snapshot.bssid ?: "unknown"
            sb.append("\nSSID: $ssid")
            sb.append("\nBSSID: $bssid")
        }

        val ip = snapshot.ipAddress
        if (ip != null) {
            sb.append("\nIP: $ip")
        }

        // Show when condition evaluation (need fresh check for reason display)
        if (whenCondition != null) {
            val result = checkConditionWithReason(context, whenCondition)
            sb.append("\nWhen: $whenCondition -> ${if (result.matched) "MATCHED" else "NOT MATCHED"}")
            if (!result.matched && result.reason != null) {
                sb.append(" (${result.reason})")
            }
        }
        if (denyCondition != null) {
            val result = checkConditionWithReason(context, denyCondition)
            sb.append("\nDeny: $denyCondition -> ${if (result.matched) "MATCHED (denied)" else "not matched"}")
        }

        return sb.toString()
    }

    /**
     * 获取当前网络类型描述（直接查询，用于 UI 展示）
     */
    fun getNetworkInfo(context: Context): String {
        val snapshot = getSnapshot(context)
        val type = snapshot.networkType

        if (type == null) {
            return "No network"
        }

        val types = mutableListOf<String>()
        when (type) {
            "wifi" -> {
                types.add("WiFi")
                val ssid = snapshot.ssid
                if (ssid != null && ssid != "<unknown ssid>") {
                    types.add("(SSID: $ssid)")
                }
            }
            "mobile" -> types.add("Mobile")
            "ethernet" -> types.add("Ethernet")
        }

        return types.joinToString(" + ")
    }

    /**
     * 获取详细网络信息，包含 IP、SSID、BSSID、类型等
     * 使用快照缓存
     */
    fun getDetailedNetworkInfo(context: Context): String {
        val snapshot = getSnapshot(context)
        val sb = StringBuilder()
        sb.append("Network Info:")

        val type = snapshot.networkType
        if (type == null) {
            sb.append("\n  No active network")
            return sb.toString()
        }

        val ipAddress = snapshot.ipAddress

        when (type) {
            "wifi" -> {
                val ssid = snapshot.ssid ?: "unknown"
                val bssid = snapshot.bssid ?: "unknown"
                sb.append("\n  [WiFi] SSID=$ssid BSSID=$bssid IP=$ipAddress")
            }
            "mobile" -> sb.append("\n  [Cellular] IP=$ipAddress")
            "ethernet" -> sb.append("\n  [Ethernet] IP=$ipAddress")
            else -> sb.append("\n  [$type] IP=$ipAddress")
        }

        return sb.toString()
    }
}
