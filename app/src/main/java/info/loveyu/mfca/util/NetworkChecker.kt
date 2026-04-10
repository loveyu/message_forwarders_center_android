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
 *   network=wifi|mobile|any          - 网络类型
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
     * 检查条件是否匹配
     */
    private fun checkCondition(context: Context, condition: String): Boolean {
        // Parse query string format: key=value,key=value
        val params = parseCondition(condition)

        // Check network type
        params["network"]?.let { network ->
            if (!checkNetworkType(context, network)) {
                return false
            }
        }

        // Check IP ranges
        params["ipRanges"]?.let { ranges ->
            if (!checkIpRange(context, ranges)) {
                return false
            }
        }

        // Check WiFi SSID
        params["ssid"]?.let { ssid ->
            if (!checkWifiSsid(context, ssid)) {
                return false
            }
        }

        // Check WiFi BSSID
        params["bssid"]?.let { bssid ->
            if (!checkWifiBssid(context, bssid)) {
                return false
            }
        }

        return true
    }

    /**
     * 检查条件是否匹配，返回详细原因
     */
    private fun checkConditionWithReason(context: Context, condition: String): ConditionResult {
        val params = parseCondition(condition)

        // Check network type
        params["network"]?.let { network ->
            if (!checkNetworkType(context, network)) {
                val currentType = getCurrentNetworkTypeString(context)
                return ConditionResult(false, "Network type mismatch: required=$network, current=$currentType")
            }
        }

        // Check IP ranges
        params["ipRanges"]?.let { ranges ->
            if (!checkIpRange(context, ranges)) {
                val currentIp = getCurrentIpAddress(context) ?: "unknown"
                return ConditionResult(false, "IP not in range: ranges=$ranges, current=$currentIp")
            }
        }

        // Check WiFi SSID
        params["ssid"]?.let { ssid ->
            if (!checkWifiSsid(context, ssid)) {
                val currentSsid = getCurrentWifiSsid(context)
                return ConditionResult(false, "SSID mismatch: required=$ssid, current=$currentSsid")
            }
        }

        // Check WiFi BSSID
        params["bssid"]?.let { bssid ->
            if (!checkWifiBssid(context, bssid)) {
                val currentBssid = getCurrentWifiBssid(context)
                return ConditionResult(false, "BSSID mismatch: required=$bssid, current=$currentBssid")
            }
        }

        return ConditionResult(true, null)
    }

    private data class ConditionResult(val matched: Boolean, val reason: String?)

    private fun getCurrentNetworkTypeString(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return "none"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "none"

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
    }

    private fun getCurrentWifiSsid(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return "none"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "none"

        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "not wifi"
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return try {
            wifiManager.connectionInfo?.ssid?.removeSurrounding("\"") ?: "unknown"
        } catch (e: SecurityException) {
            "permission denied"
        }
    }

    private fun getCurrentWifiBssid(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return "none"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "none"

        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "not wifi"
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return try {
            wifiManager.connectionInfo?.bssid ?: "unknown"
        } catch (e: SecurityException) {
            "permission denied"
        }
    }

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
     * 检查网络类型
     */
    private fun checkNetworkType(context: Context, type: String): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return type == "any"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return when (type.lowercase()) {
            "wifi" -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            "mobile" -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            "any" -> true
            else -> true
        }
    }

    /**
     * 检查 IP 段
     */
    private fun checkIpRange(context: Context, ipRanges: String): Boolean {
        val currentIp = getCurrentIpAddress(context) ?: return false
        val ranges = ipRanges.split(",").map { it.trim() }

        return ranges.any { range ->
            isIpInRange(currentIp, range)
        }
    }

    /**
     * 检查 WiFi SSID
     */
    private fun checkWifiSsid(context: Context, ssidPattern: String): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        // Must be WiFi
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return false
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val currentSsid = try {
            wifiManager.connectionInfo?.ssid?.removeSurrounding("\"") ?: ""
        } catch (e: SecurityException) {
            LogManager.appendLog("NETWORK", "Cannot access WiFi SSID: ${e.message}")
            return false
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
    private fun checkWifiBssid(context: Context, bssidPattern: String): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        // Must be WiFi
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return false
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val currentBssid = try {
            wifiManager.connectionInfo?.bssid
        } catch (e: SecurityException) {
            LogManager.appendLog("NETWORK", "Cannot access WiFi BSSID: ${e.message}")
            return false
        } ?: return false

        // bssidPattern can be comma-separated list
        val bssids = bssidPattern.split(",").map { it.trim() }
        return bssids.any { it == currentBssid }
    }

    /**
     * 获取当前 IP 地址
     */
    private fun getCurrentIpAddress(context: Context): String? {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return null
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null

            // Check WiFi first
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                val ipAddress = wifiInfo?.ipAddress
                if (ipAddress != null && ipAddress != 0) {
                    return formatIpAddress(ipAddress)
                }
            }

            // Check other interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.appendLog("NETWORK", "Failed to get IP address: ${e.message}")
        }
        return null
    }

    private fun formatIpAddress(ipAddress: Int): String {
        return "${ipAddress and 0xFF}.${ipAddress shr 8 and 0xFF}.${ipAddress shr 16 and 0xFF}.${ipAddress shr 24 and 0xFF}"
    }

    /**
     * 检查 IP 是否在段内
     */
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
            LogManager.appendLog("NETWORK", "IP range check error: ${e.message}")
            false
        }
    }

    /**
     * 获取当前网络条件与 when/deny 的匹配详情
     */
    fun getMatchedConditions(context: Context, whenCondition: String?, denyCondition: String?): String {
        val sb = StringBuilder()

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

        if (capabilities != null) {
            val type = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Unknown"
            }
            sb.append("Network: $type")

            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                try {
                    val ssid = wifiManager.connectionInfo?.ssid?.removeSurrounding("\"") ?: "unknown"
                    val bssid = wifiManager.connectionInfo?.bssid ?: "unknown"
                    sb.append("\nSSID: $ssid")
                    sb.append("\nBSSID: $bssid")
                } catch (e: SecurityException) {
                    sb.append("\nWiFi info: permission denied")
                }
            }

            val ip = getCurrentIpAddress(context)
            if (ip != null) {
                sb.append("\nIP: $ip")
            }
        } else {
            sb.append("Network: none")
        }

        // Show when condition evaluation
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
     * 获取当前网络类型描述
     */
    fun getNetworkInfo(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        if (capabilities == null) {
            return "No network"
        }

        val types = mutableListOf<String>()
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            types.add("WiFi")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            try {
                wifiManager.connectionInfo?.ssid?.let { ssid ->
                    types.add("(SSID: ${ssid.removeSurrounding("\"")})")
                }
            } catch (e: SecurityException) {
                types.add("(WiFi info unavailable)")
            }
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            types.add("Mobile")
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            types.add("Ethernet")
        }

        return types.joinToString(" + ")
    }

    /**
     * 获取详细网络信息，包含 IP、MAC、SSID、BSSID、类型等
     * 仅使用当前活跃网络，避免重复信息
     */
    fun getDetailedNetworkInfo(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val sb = StringBuilder()
        sb.append("Network Info:")

        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork == null) {
            sb.append("\n  No active network")
            return sb.toString()
        }

        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (capabilities == null) {
            sb.append("\n  No capabilities")
            return sb.toString()
        }

        // Get IP address for active network
        val ipAddress = try {
            val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
            linkProperties?.linkAddresses?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }?.address?.hostAddress
        } catch (e: Exception) {
            null
        }

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            try {
                val wifiInfo = wifiManager.connectionInfo
                val ssid = wifiInfo?.ssid?.removeSurrounding("\"") ?: "unknown"
                val bssid = wifiInfo?.bssid ?: "unknown"
                sb.append("\n  [WiFi] SSID=$ssid BSSID=$bssid IP=$ipAddress")
            } catch (e: SecurityException) {
                sb.append("\n  [WiFi] info unavailable: ${e.message}")
            }
        }

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            sb.append("\n  [Cellular] IP=$ipAddress")
        }

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            sb.append("\n  [Ethernet] IP=$ipAddress")
        }

        return sb.toString()
    }

    /**
     * 获取当前 WiFi MAC 地址
     */
    private fun getMacAddress(wifiManager: WifiManager): String {
        try {
            val wifiInfo = wifiManager.connectionInfo
            val mac = wifiInfo?.macAddress
            if (mac != null && mac != "02:00:00:00:00:00") {
                return mac
            }
        } catch (e: SecurityException) {
            // Fallback
        }

        // Try to get MAC from network interfaces
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val macBytes = networkInterface.hardwareAddress ?: continue
                if (macBytes.isNotEmpty() && macBytes.size == 6) {
                    return macBytes.joinToString(":") { String.format("%02X", it) }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        return "unknown"
    }
}
