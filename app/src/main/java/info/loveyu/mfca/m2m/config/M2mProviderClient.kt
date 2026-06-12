package info.loveyu.mfca.m2m.config

import android.util.Log
import info.loveyu.mfca.m2m.models.M2mProviderInfo
import info.loveyu.mfca.m2m.models.M2mProviderType
import info.loveyu.mfca.m2m.models.M2mVehicleType
import info.loveyu.mfca.util.LogManager
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

internal object M2mProviderClient {
    fun fetchProviders(apiPort: Int, apiSecret: String): Result<Map<String, M2mProviderInfo>> {
        if (apiPort == 0) return Result.failure(IllegalStateException("API not ready"))
        return try {
            val providers = mutableMapOf<String, M2mProviderInfo>()

            val proxyUrl = URL("http://127.0.0.1:$apiPort/providers/proxies")
            val proxyConn = proxyUrl.openConnection() as HttpURLConnection
            proxyConn.requestMethod = "GET"
            proxyConn.connectTimeout = 2000
            proxyConn.readTimeout = 2000
            if (apiSecret.isNotBlank()) {
                proxyConn.setRequestProperty("Authorization", "Bearer $apiSecret")
            }
            val proxyBody = proxyConn.inputStream.bufferedReader().use { it.readText() }
            proxyConn.disconnect()
            val proxyJson = JSONObject(proxyBody)
            val proxyProviders = proxyJson.optJSONObject("providers")
            if (proxyProviders != null) {
                for (key in proxyProviders.keys()) {
                    val obj = proxyProviders.getJSONObject(key)
                    val subInfo = obj.optJSONObject("subscriptionInfo")
                    providers[key] = M2mProviderInfo(
                        name = obj.optString("name", key),
                        type = M2mProviderType.Proxy,
                        vehicleType = parseVehicleType(obj.optString("vehicleType", "")),
                        updatedAt = obj.optString("updatedAt", ""),
                        proxyCount = obj.optJSONArray("proxies")?.length() ?: 0,
                        ruleCount = 0,
                        subscriptionUrl = subInfo?.optString("URL", "") ?: "",
                    )
                }
            }

            val ruleUrl = URL("http://127.0.0.1:$apiPort/providers/rules")
            val ruleConn = ruleUrl.openConnection() as HttpURLConnection
            ruleConn.requestMethod = "GET"
            ruleConn.connectTimeout = 2000
            ruleConn.readTimeout = 2000
            if (apiSecret.isNotBlank()) {
                ruleConn.setRequestProperty("Authorization", "Bearer $apiSecret")
            }
            val ruleBody = ruleConn.inputStream.bufferedReader().use { it.readText() }
            ruleConn.disconnect()
            val ruleJson = JSONObject(ruleBody)
            val ruleProviders = ruleJson.optJSONObject("providers")
            if (ruleProviders != null) {
                for (key in ruleProviders.keys()) {
                    val obj = ruleProviders.getJSONObject(key)
                    providers[key] = M2mProviderInfo(
                        name = obj.optString("name", key),
                        type = M2mProviderType.Rule,
                        vehicleType = parseVehicleType(obj.optString("vehicleType", "")),
                        updatedAt = obj.optString("updatedAt", ""),
                        proxyCount = 0,
                        ruleCount = obj.optInt("ruleCount", 0),
                    )
                }
            }

            Result.success(providers)
        } catch (e: Exception) {
            Log.w("VPN", "Failed to fetch providers: ${e.message}")
            Result.failure(e)
        }
    }

    fun updateProvider(apiPort: Int, apiSecret: String, name: String, type: M2mProviderType): Result<Unit> {
        if (apiPort == 0) return Result.failure(IllegalStateException("API not ready"))
        return try {
            val endpoint = when (type) {
                M2mProviderType.Proxy -> "proxies"
                M2mProviderType.Rule -> "rules"
            }
            val url = URL("http://127.0.0.1:$apiPort/providers/$endpoint/$name")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (apiSecret.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiSecret")
            }
            conn.outputStream.write("{}".toByteArray())
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) Result.success(Unit) else Result.failure(Exception("HTTP $code"))
        } catch (e: Exception) {
            Log.w("VPN", "Failed to update provider $name: ${e.message}")
            Result.failure(e)
        }
    }

    fun updateAllProviders(apiPort: Int, apiSecret: String, type: M2mProviderType): Result<Unit> {
        if (apiPort == 0) return Result.failure(IllegalStateException("API not ready"))
        return try {
            val endpoint = when (type) {
                M2mProviderType.Proxy -> "proxies"
                M2mProviderType.Rule -> "rules"
            }
            val url = URL("http://127.0.0.1:$apiPort/providers/$endpoint")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (apiSecret.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiSecret")
            }
            conn.outputStream.write("{}".toByteArray())
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) Result.success(Unit) else Result.failure(Exception("HTTP $code"))
        } catch (e: Exception) {
            Log.w("VPN", "Failed to update all providers: ${e.message}")
            Result.failure(e)
        }
    }

    private fun parseVehicleType(raw: String): M2mVehicleType {
        return when (raw.lowercase()) {
            "http" -> M2mVehicleType.HTTP
            "file" -> M2mVehicleType.File
            "compatible" -> M2mVehicleType.Compatible
            else -> M2mVehicleType.HTTP
        }
    }
}
