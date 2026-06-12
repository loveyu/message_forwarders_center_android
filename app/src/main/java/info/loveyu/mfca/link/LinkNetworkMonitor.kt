package info.loveyu.mfca.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import info.loveyu.mfca.input.InputManager
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.service.refreshStats
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.network.NetworkChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class LinkNetworkMonitor(
    private val onResetAllFailureCounts: () -> Unit,
    private val onDisconnectAll: () -> Unit,
    private val onCheckAllLinkConditions: () -> Unit,
    private val onTriggerMqttKeepAliveProbe: (String) -> Unit,
    private val isInitialized: () -> Boolean,
) {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var isNetworkAvailable = false
    @Volatile
    var currentNetworkType = LinkManager.NetworkType.UNKNOWN
    @Volatile
    private var lastNetworkTypeUpdateTime = 0L
    @Volatile
    private var lastTransportType = LinkManager.NetworkType.UNKNOWN
    @Volatile
    private var lastWifiBssid: String? = null

    private val _networkStateVersion = MutableStateFlow(0)
    val networkStateVersion: StateFlow<Int> = _networkStateVersion.asStateFlow()

    fun signalStateChange() {
        _networkStateVersion.value++
    }

    fun startMonitoring(ctx: Context) {
        val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LogManager.logInfo("LINK", "Network available")
                isNetworkAvailable = true
                onResetAllFailureCounts()
                NetworkChecker.invalidateCache()
                lastWifiBssid = NetworkChecker.getCurrentBssid(ctx)
                updateNetworkType(ctx)
                ForwardService.triggerTick()
            }

            override fun onLost(network: Network) {
                LogManager.logWarn("LINK", "Network lost")
                NetworkChecker.invalidateCache()
                onResetAllFailureCounts()

                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val hasNetwork = cm.activeNetwork != null
                isNetworkAvailable = hasNetwork

                if (!hasNetwork && isInitialized()) {
                    onDisconnectAll()
                    InputManager.stopAllLinkBased()
                    InputManager.stopAllUdp2Raw()
                }

                if (!hasNetwork) {
                    lastWifiBssid = null
                }
                updateNetworkType(ctx)
                ForwardService.triggerTick()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val previousBssid = lastWifiBssid
                val previousSsid = if (previousBssid != null) NetworkChecker.getCurrentSsid(ctx) else null

                NetworkChecker.invalidateCache()

                val newType = when {
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LinkManager.NetworkType.WIFI
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> LinkManager.NetworkType.MOBILE
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LinkManager.NetworkType.ETHERNET
                    else -> LinkManager.NetworkType.UNKNOWN
                }
                val newBssid = if (newType == LinkManager.NetworkType.WIFI) NetworkChecker.getCurrentBssid(ctx) else null
                val newSsid = if (newType == LinkManager.NetworkType.WIFI) NetworkChecker.getCurrentSsid(ctx) else null
                val bssidChanged = newType == LinkManager.NetworkType.WIFI &&
                    previousBssid != null &&
                    newBssid != null &&
                    previousBssid != newBssid
                lastWifiBssid = newBssid

                if (bssidChanged) {
                    LogManager.logInfo("LINK", "WiFi BSSID changed: ${previousBssid} -> ${newBssid} (ssid=${newSsid ?: previousSsid ?: "unknown"})")
                    onTriggerMqttKeepAliveProbe("bssid_changed")
                    ForwardService.triggerTick()
                }

                if (newType == lastTransportType) return
                LogManager.logDebug("LINK", "Transport changed: $lastTransportType -> $newType")
                lastTransportType = newType
                onResetAllFailureCounts()
                InputManager.stopAllUdp2Raw()
                updateNetworkType(ctx)
                ForwardService.triggerTick()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                NetworkChecker.invalidateCache()
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
            val activeNetwork = connectivityManager.activeNetwork
            isNetworkAvailable = activeNetwork != null
            lastWifiBssid = NetworkChecker.getCurrentBssid(ctx)
            updateNetworkType(ctx)
        } catch (e: Exception) {
            LogManager.logError("LINK", "Failed to register network callback: ${e.message}")
        }
    }

    fun updateNetworkType(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastNetworkTypeUpdateTime < 1_000L) return
        lastNetworkTypeUpdateTime = now

        val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        currentNetworkType = if (capabilities != null) {
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LinkManager.NetworkType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> LinkManager.NetworkType.MOBILE
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LinkManager.NetworkType.ETHERNET
                else -> LinkManager.NetworkType.UNKNOWN
            }
        } else {
            LinkManager.NetworkType.UNKNOWN
        }
        lastTransportType = currentNetworkType
        lastWifiBssid = if (currentNetworkType == LinkManager.NetworkType.WIFI) NetworkChecker.getCurrentBssid(ctx) else null

        LogManager.logDebug("LINK", "Network type: $currentNetworkType")
        LogManager.logDebug("NETWORK", NetworkChecker.getDetailedNetworkInfo(ctx))

        _networkStateVersion.value++

        ForwardService.refreshStats()

        if (!isInitialized()) return

        if (network == null) {
            onDisconnectAll()
            return
        }

        onCheckAllLinkConditions()
        InputManager.checkAllInputConditions()
    }

    fun unregisterCallback(ctx: Context) {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        networkCallback = null
    }
}
