package info.loveyu.mfca.m2m.traffic

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.HttpURLConnection
import java.net.URL
import info.loveyu.mfca.m2m.core.M2mBridgeProcessManager
import info.loveyu.mfca.m2m.models.M2mTrafficStats
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.flow.asStateFlow

internal class M2mTrafficStatsCollector {
    @Volatile var tunInterfaceName: String? = null
    @Volatile var apiPort: Int = 0
    @Volatile var apiSecret: String = ""

    private var baselineRxBytes: Long = 0L
    private var baselineTxBytes: Long = 0L
    private var prevRxBytes: Long = 0L
    private var prevTxBytes: Long = 0L
    private var prevTimestampMs: Long = 0L
    private var trafficPollJob: Job? = null
    private val trafficStatsFlow = MutableStateFlow<M2mTrafficStats?>(null)
    val trafficStats: StateFlow<M2mTrafficStats?> = trafficStatsFlow.asStateFlow()

    fun onTunEstablished(ifaceName: String?) {
        tunInterfaceName = ifaceName
        val pair = ifaceName?.let { readTrafficFromProcNetDev(it) }
        if (pair != null) {
            baselineRxBytes = pair.first
            baselineTxBytes = pair.second
            LogManager.logInfo("VPN", "TUN established: $ifaceName, baseline rx=${formatBytes(baselineRxBytes)} tx=${formatBytes(baselineTxBytes)}")
        } else {
            baselineRxBytes = 0L
            baselineTxBytes = 0L
            LogManager.logDebug("VPN", "TUN established: $ifaceName, /proc/net/dev not readable (expected on API 34+), bridge stats will be used")
        }
        prevRxBytes = baselineRxBytes
        prevTxBytes = baselineTxBytes
        prevTimestampMs = System.currentTimeMillis()
        trafficStatsFlow.value = null
    }

    fun onApiReady(port: Int, secret: String, scope: CoroutineScope) {
        apiPort = port
        apiSecret = secret
        LogManager.logInfo("VPN", "API ready for traffic stats: port=$port")
        stopTrafficPolling()
        trafficPollJob = scope.launch {
            while (isActive) {
                refreshTrafficStats()
                delay(1000)
            }
        }
    }

    fun stopTrafficPolling() {
        trafficPollJob?.cancel()
        trafficPollJob = null
    }

    fun onTunDestroyed() {
        stopTrafficPolling()
        tunInterfaceName = null
        apiPort = 0
        apiSecret = ""
        baselineRxBytes = 0L
        baselineTxBytes = 0L
        prevRxBytes = 0L
        prevTxBytes = 0L
        prevTimestampMs = 0L
        trafficStatsFlow.value = null
    }

    fun refreshTrafficStats() {
        val now = System.currentTimeMillis()

        val bridgeStats = M2mBridgeProcessManager.queryStats()
        if (bridgeStats != null) {
            computeTrafficStats(bridgeStats.rxBytes, bridgeStats.txBytes, now)
            return
        }

        val apiPair = fetchTrafficFromApi()
        if (apiPair != null) {
            computeTrafficStats(apiPair.first, apiPair.second, now)
            return
        }

        val iface = tunInterfaceName ?: return
        val pair = readTrafficFromProcNetDev(iface) ?: return
        computeTrafficStats(pair.first, pair.second, now)
    }

    private fun computeTrafficStats(rx: Long, tx: Long, now: Long) {
        if (prevTimestampMs == 0L) {
            baselineRxBytes = rx
            baselineTxBytes = tx
            prevRxBytes = rx
            prevTxBytes = tx
            prevTimestampMs = now
            trafficStatsFlow.value = M2mTrafficStats(
                totalRxBytes = 0L,
                totalTxBytes = 0L,
            )
            return
        }
        val elapsed = now - prevTimestampMs
        val rxDelta = (rx - prevRxBytes).coerceAtLeast(0)
        val txDelta = (tx - prevTxBytes).coerceAtLeast(0)
        val rxSpeed = if (elapsed > 0) rxDelta * 1000 / elapsed else 0L
        val txSpeed = if (elapsed > 0) txDelta * 1000 / elapsed else 0L
        trafficStatsFlow.value = M2mTrafficStats(
            totalRxBytes = (rx - baselineRxBytes).coerceAtLeast(0),
            totalTxBytes = (tx - baselineTxBytes).coerceAtLeast(0),
            rxSpeed = rxSpeed,
            txSpeed = txSpeed,
        )
        prevRxBytes = rx
        prevTxBytes = tx
        prevTimestampMs = now
    }

    fun logTrafficStats() {
        val iface = tunInterfaceName ?: return
        val pair = readTrafficFromProcNetDev(iface) ?: return
        val (rx, tx) = pair
        LogManager.logDebug(
            "VPN",
            "Traffic: ↓${formatBytes(rx)} ↑${formatBytes(tx)} | Session: ↓${formatBytes((rx - baselineRxBytes).coerceAtLeast(0))} ↑${formatBytes((tx - baselineTxBytes).coerceAtLeast(0))}",
        )
    }

    private fun fetchTrafficFromApi(): Pair<Long, Long>? {
        val port = apiPort
        if (port == 0) return null
        return try {
            val url = URL("http://127.0.0.1:$port/connections")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            if (apiSecret.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiSecret")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val down = json.optLong("downloadTotal", -1)
            val up = json.optLong("uploadTotal", -1)
            if (down >= 0 && up >= 0) Pair(down, up) else null
        } catch (e: Exception) {
            Log.w("VPN", "API traffic fetch failed: ${e.message}")
            null
        }
    }

    companion object {
        fun readTrafficFromProcNetDev(iface: String): Pair<Long, Long>? {
            return try {
                val file = File("/proc/net/dev")
                if (!file.exists() || !file.canRead()) return null
                val reader = BufferedReader(FileReader(file))
                val content = reader.use { it.readText() }
                val lines = content.lines()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith(iface + ":")) {
                        val parts = trimmed.split("\\s+".toRegex())
                        // parts[0] = "iface:" - already matched by startsWith
                        // parts[1] = rxBytes
                        // parts[9] = txBytes
                        if (parts.size > 9) {
                            val rxBytes = parts[1].toLongOrNull() ?: return null
                            val txBytes = parts[9].toLongOrNull() ?: return null
                            return Pair(rxBytes, txBytes)
                        }
                        return null
                    }
                }
                null
            } catch (e: Exception) {
                LogManager.logWarn("VPN", "Failed to read /proc/net/dev: ${e.message}")
                null
            }
        }

        fun formatBytes(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
                else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
            }
        }
    }
}
