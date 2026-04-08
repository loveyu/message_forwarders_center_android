package info.loveyu.mfca.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 网络状态检查器
 */
object NetworkChecker {

    /**
     * 检查链接是否应该启用
     */
    fun shouldEnable(context: Context, condition: info.loveyu.mfca.config.LinkEnabledCondition?): Boolean {
        if (condition == null) return true

        val networkCondition = condition.network
        val wifiCondition = condition.wifi

        // Check network type
        if (networkCondition != null) {
            if (!checkNetworkType(context, networkCondition)) {
                return false
            }

            // Check IP range if specified
            if (networkCondition.ipRanges != null && networkCondition.ipRanges.isNotEmpty()) {
                if (!checkIpRange(context, networkCondition.ipRanges)) {
                    return false
                }
            }
        }

        // Check WiFi conditions
        if (wifiCondition != null) {
            if (!checkWifiCondition(context, wifiCondition)) {
                return false
            }
        }

        return true
    }

    /**
     * 检查网络类型
     */
    private fun checkNetworkType(context: Context, condition: info.loveyu.mfca.config.NetworkCondition): Boolean {
        val type = condition.type ?: return true

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return type == info.loveyu.mfca.config.NetworkType.any
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return when (type) {
            info.loveyu.mfca.config.NetworkType.wifi -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            info.loveyu.mfca.config.NetworkType.mobile -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            info.loveyu.mfca.config.NetworkType.any -> true
        }
    }

    /**
     * 检查 IP 段
     */
    private fun checkIpRange(context: Context, ipRanges: List<String>): Boolean {
        val currentIp = getCurrentIpAddress(context) ?: return false

        return ipRanges.any { range ->
            isIpInRange(currentIp, range)
        }
    }

    /**
     * 检查 WiFi 条件
     */
    private fun checkWifiCondition(context: Context, condition: info.loveyu.mfca.config.WifiCondition): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        // Must be WiFi
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return false
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo ?: return false

        // Check SSID
        if (condition.ssid != null && condition.ssid.isNotEmpty()) {
            val currentSsid = wifiInfo.ssid?.removeSurrounding("\"") ?: ""
            val matchesSsid = condition.ssid.any { pattern ->
                if (pattern.startsWith("~")) {
                    // Regex pattern
                    Regex(pattern.removePrefix("~")).matches(currentSsid)
                } else {
                    currentSsid == pattern
                }
            }
            if (!matchesSsid) {
                return false
            }
        }

        // Check BSSID
        if (condition.bssid != null && condition.bssid.isNotEmpty()) {
            val currentBssid = wifiInfo.bssid ?: return false
            val matchesBssid = condition.bssid.any { it == currentBssid }
            if (!matchesBssid) {
                return false
            }
        }

        return true
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
