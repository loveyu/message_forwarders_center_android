package info.loveyu.mfca.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

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
    internal data class NetworkSnapshot(
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
    private fun getSnapshot(context: Context, forceRefresh: Boolean = false): NetworkSnapshot {
        if (!forceRefresh) {
            cachedSnapshot?.let { return it }
        }

        val snapshot = querySnapshot(context)
        cachedSnapshot = snapshot
        return snapshot
    }

    fun refreshCache(context: Context) {
        getSnapshot(context, forceRefresh = true)
    }

    /**
     * 强制刷新快照（网络变更事件时调用）
     */
    fun invalidateCache() {
        cachedSnapshot = null
    }

    fun getCurrentSsid(context: Context): String? = getSnapshot(context).ssid

    fun getCurrentBssid(context: Context): String? {
        val bssid = getSnapshot(context).bssid ?: return null
        return if (bssid == "02:00:00:00:00:00" || bssid.isBlank()) null else bssid
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
            ipAddress = queryPrimaryIpFromInterfaces()
        }

        return NetworkSnapshot(networkType, ssid, bssid, ipAddress)
    }

    fun getAllLocalIpv4Addresses(): List<String> {
        return queryIpv4AddressesFromInterfaces()
    }

    private fun queryPrimaryIpFromInterfaces(): String? {
        return queryIpv4AddressesFromInterfaces().firstOrNull()
    }

    private fun queryIpv4AddressesFromInterfaces(): List<String> {
        val results = linkedSetOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val inetAddresses = ni.inetAddresses
                while (inetAddresses.hasMoreElements()) {
                    val address = inetAddresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        address.hostAddress?.takeIf { it.isNotBlank() }?.let { results.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return results.toList()
    }

    /**
     * 检查链接是否应该启用
     */
    fun shouldEnable(context: Context, whenCondition: String?, denyCondition: String?): Boolean {
        if (denyCondition != null && NetworkConditionChecker.checkCondition(context, denyCondition, this::getSnapshot)) return false
        if (whenCondition != null && !NetworkConditionChecker.checkCondition(context, whenCondition, this::getSnapshot)) return false
        return true
    }

    fun getEnableReason(
        context: Context,
        whenCondition: String?,
        denyCondition: String?,
        forceRefresh: Boolean = false,
    ): EnableResult {
        if (denyCondition != null) {
            val denyResult = NetworkConditionChecker.checkConditionWithReason(context, denyCondition, this::getSnapshot, forceRefresh)
            if (denyResult.matched) return EnableResult(enabled = false, reason = "Denied by condition: $denyCondition")
        }

        if (whenCondition != null) {
            val whenResult = NetworkConditionChecker.checkConditionWithReason(context, whenCondition, this::getSnapshot, forceRefresh)
            if (!whenResult.matched) return EnableResult(enabled = false, reason = "Condition not met: $whenCondition")
        }

        return EnableResult(enabled = true, reason = null)
    }

    data class EnableResult(val enabled: Boolean, val reason: String?)

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
            LogManager.logWarn("NETWORK", "IP range check error: ${e.message}")
            false
        }
    }

    /**
     * 获取当前网络条件与 when/deny 的匹配详情（直接查询，用于 UI 展示）
     */
    fun getMatchedConditions(
        context: Context,
        whenCondition: String?,
        denyCondition: String?,
        forceRefresh: Boolean = false
    ): String {
        val sb = StringBuilder()
        val snapshot = getSnapshot(context, forceRefresh)

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
            val result = NetworkConditionChecker.checkConditionWithReason(context, whenCondition, this::getSnapshot, forceRefresh)
            sb.append("\nWhen: $whenCondition -> ${if (result.matched) "MATCHED" else "NOT MATCHED"}")
            if (!result.matched && result.reason != null) {
                sb.append(" (${result.reason})")
            }
        }
        if (denyCondition != null) {
            val result = NetworkConditionChecker.checkConditionWithReason(context, denyCondition, this::getSnapshot, forceRefresh)
            sb.append("\nDeny: $denyCondition -> ${if (result.matched) "MATCHED (denied)" else "not matched"}")
        }

        return sb.toString()
    }

    /**
     * 获取当前网络类型描述（直接查询，用于 UI 展示）
     */
    fun getNetworkInfo(context: Context, forceRefresh: Boolean = false): String {
        val snapshot = getSnapshot(context, forceRefresh)
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
    fun getDetailedNetworkInfo(context: Context, forceRefresh: Boolean = false): String {
        val snapshot = getSnapshot(context, forceRefresh)
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
