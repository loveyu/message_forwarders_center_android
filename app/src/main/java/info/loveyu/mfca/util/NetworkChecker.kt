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
 *   when: network=wifi,ssid=MyHomeWiFi
 *   when: network=wifi,ipRanges=192.168.1.0/24
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
     * 解析条件字符串为 key=value map
     * 格式: network=wifi,ssid=MyWiFi,~Regex.*
     * 值如果是列表用逗号分隔，但等号后面的整个值作为一个值
     */
    private fun parseCondition(condition: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pairs = condition.split(",")
        for (pair in pairs) {
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                val key = URLDecoder.decode(kv[0].trim(), "UTF-8")
                val value = URLDecoder.decode(kv[1].trim(), "UTF-8")
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
        val wifiInfo = wifiManager.connectionInfo ?: return false
        val currentSsid = wifiInfo.ssid?.removeSurrounding("\"") ?: ""

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
        val wifiInfo = wifiManager.connectionInfo ?: return false
        val currentBssid = wifiInfo.bssid ?: return false

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
    private fun isIpInRange(ip: String, cidr: String): Boolean {
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
            val wifiInfo = wifiManager.connectionInfo
            wifiInfo?.ssid?.let { ssid ->
                types.add("(SSID: ${ssid.removeSurrounding("\"")})")
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
}
