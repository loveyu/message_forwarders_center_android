package info.loveyu.mfca.vpn

import android.content.Context
import info.loveyu.mfca.config.VpnInputConfig
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object VpnProfileManager {
    private val yamlLoad = Load(LoadSettings.builder().build())
    private val yamlDump = Dump(DumpSettings.builder().build())

    fun ensureProfile(context: Context, config: VpnInputConfig, localProxyPort: Int): Result<File> {
        return runCatching {
            require(config.configUrl.startsWith("http://") || config.configUrl.startsWith("https://")) {
                "configUrl only supports http/https"
            }
            val targetDir = File(context.filesDir, "vpn/profiles")
            val sourceFile = File(targetDir, "${config.name}.source.yaml")
            val targetFile = File(targetDir, "${config.name}.runtime.yaml")
            targetDir.mkdirs()
            val connection = openConnection(config.configUrl)
            try {
                connection.inputStream.use { input ->
                    FileOutputStream(sourceFile).use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
            targetFile.writeText(buildRuntimeProfileContent(sourceFile.readText(), localProxyPort))
            targetFile
        }
    }

    internal fun buildRuntimeProfileContent(source: String, localProxyPort: Int): String {
        val root = yamlLoad.loadFromString(source) as? Map<*, *>
            ?: throw IllegalStateException("VPN profile must be a YAML mapping")
        val normalized = LinkedHashMap<String, Any?>()
        root.forEach { (key, value) ->
            if (key != null) {
                normalized[key.toString()] = normalizeYamlValue(value)
            }
        }

        normalized["mixed-port"] = localProxyPort
        normalized["allow-lan"] = false
        normalized["bind-address"] = "127.0.0.1"
        val tun = (normalized["tun"] as? Map<*, *>)?.let { existing ->
            LinkedHashMap<String, Any?>().apply {
                existing.forEach { (key, value) ->
                    if (key != null) {
                        put(key.toString(), normalizeYamlValue(value))
                    }
                }
            }
        } ?: LinkedHashMap()
        tun["enable"] = false
        normalized["tun"] = tun

        return yamlDump.dumpToString(normalized)
    }

    private fun normalizeYamlValue(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> LinkedHashMap<String, Any?>().apply {
                value.forEach { (key, nestedValue) ->
                    if (key != null) {
                        put(key.toString(), normalizeYamlValue(nestedValue))
                    }
                }
            }
            is List<*> -> value.map { normalizeYamlValue(it) }
            else -> value
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "FlowGate-Android")
        }
        if (connection.responseCode !in 200..299) {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
            throw IllegalStateException("Profile download failed: HTTP ${connection.responseCode}${if (error.isNullOrBlank()) "" else " - $error"}")
        }
        return connection
    }
}
