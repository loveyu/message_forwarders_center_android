package info.loveyu.mfca.vpn

import android.content.Context
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

object VpnProfileManager {
    private val yamlLoad = Load(LoadSettings.builder().build())
    private val yamlDump = Dump(DumpSettings.builder().build())

    fun buildRuntimeProfile(
        context: Context,
        candidateName: String,
        sourceContent: String,
        localProxyPort: Int,
        ruleMode: VpnRuleMode? = null,
        logLevel: VpnLogLevel? = null,
    ): Result<File> {
        return runCatching {
            val targetDir = File(context.filesDir, "vpn/profiles")
            val targetFile = File(targetDir, "$candidateName.runtime.yaml")
            targetDir.mkdirs()
            targetFile.writeText(buildRuntimeProfileContent(sourceContent, localProxyPort, ruleMode, logLevel))
            targetFile
        }
    }

    internal fun buildRuntimeProfileContent(
        source: String,
        localProxyPort: Int,
        ruleMode: VpnRuleMode? = null,
        logLevel: VpnLogLevel? = null,
    ): String {
        val root =
            yamlLoad.loadFromString(source) as? Map<*, *>
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
        if (ruleMode != null) normalized["mode"] = ruleMode.name
        if (logLevel != null) normalized["log-level"] = logLevel.name
        val tun =
            (normalized["tun"] as? Map<*, *>)?.let { existing ->
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

        val dns =
            (normalized["dns"] as? Map<*, *>)?.let { existing ->
                LinkedHashMap<String, Any?>().apply {
                    existing.forEach { (key, value) ->
                        if (key != null) {
                            put(key.toString(), normalizeYamlValue(value))
                        }
                    }
                }
            } ?: linkedMapOf<String, Any?>()
        dns["enable"] = true
        dns["listen"] = "127.0.0.1:${MfcaVpnService.MIHOMO_DNS_PORT}"
        dns["enhanced-mode"] = "fake-ip"
        if (!dns.containsKey("fake-ip-range")) {
            dns["fake-ip-range"] = "28.0.0.1/8"
        }
        if (!dns.containsKey("fake-ip-filter")) {
            @Suppress("ktlint:standard:argument-list-wrapping")
            dns["fake-ip-filter"] = listOf(
                "*.lan", "*.local", "*.localhost",
                "*.mshome.net",
                "dns.msftncsi.com", "www.msftncsi.com", "www.msftconnecttest.com",
                "dl.google.com", "dl.l.google.com",
            )
        }
        if (!dns.containsKey("default-nameserver")) {
            dns["default-nameserver"] = listOf("223.5.5.5", "119.29.29.29")
        }
        if (!dns.containsKey("nameserver")) {
            dns["nameserver"] = listOf(
                "223.5.5.5", "119.29.29.29",
                "https://doh.pub/dns-query",
                "https://dns.alidns.com/dns-query",
            )
        }
        normalized["dns"] = dns

        return yamlDump.dumpToString(normalized)
    }

    private fun normalizeYamlValue(value: Any?): Any? {
        return when (value) {
            is Map<*, *> ->
                LinkedHashMap<String, Any?>().apply {
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
}

