package info.loveyu.mfca.config

import info.loveyu.mfca.config.models.Duration
import info.loveyu.mfca.config.models.HttpInputConfig
import info.loveyu.mfca.config.models.InputsConfig
import info.loveyu.mfca.config.models.LinkInputConfig
import info.loveyu.mfca.config.models.M2mAccessControlMode
import info.loveyu.mfca.config.models.M2mInputConfig
import info.loveyu.mfca.config.models.ReplayConfig
import info.loveyu.mfca.config.models.ReplayProvider
import info.loveyu.mfca.config.models.Udp2RawInputConfig

internal object InputConfigParser {

    fun parse(inputs: Any?): InputsConfig {
        if (inputs == null) return InputsConfig()
        val map = inputs as Map<String, Any>
        return InputsConfig(
            http = parseHttpInputs(map["http"]),
            link = parseLinkInputs(map["link"]),
            udp2raw = parseUdp2RawInputs(map["udp2raw"]),
            m2m = parseM2mInputs(map["m2m"]),
        )
    }

    private fun parseHttpInputs(http: Any?): List<HttpInputConfig> {
        if (http == null) return emptyList()
        return (http as List<*>).mapNotNull { input ->
            (input as? Map<String, Any>)?.let { map ->
                HttpInputConfig(
                    name = map["name"] as? String ?: return@mapNotNull null,
                    dsn = map["dsn"] as? String ?: return@mapNotNull null,
                    paths = (map["paths"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                    linkId = map["linkId"] as? String,
                    whenCondition = map["when"] as? String,
                    deny = map["deny"] as? String,
                    plugins = parseInputPluginConfig(map["plugins"] as? Map<String, Any>),
                )
            }
        }
    }

    private fun parseLinkInputs(link: Any?): List<LinkInputConfig> {
        if (link == null) return emptyList()
        return (link as List<*>).mapNotNull { input ->
            (input as? Map<String, Any>)?.let { map ->
                val linkIds = parseStringOrList(map["linkId"])
                if (linkIds.isEmpty()) return@mapNotNull null
                LinkInputConfig(
                    name = map["name"] as? String ?: return@mapNotNull null,
                    linkIds = linkIds,
                    role = parseLinkRole(map["role"] as? String),
                    topic = map["topic"] as? String,
                    topics = (map["topics"] as? List<*>)?.mapNotNull { it as? String },
                    excludeTopics = (map["excludeTopics"] as? List<*>)?.mapNotNull { it as? String },
                    qos = (map["qos"] as? Number)?.toInt(),
                    replay = parseReplay(map["replay"]),
                    whenCondition = map["when"] as? String,
                    deny = map["deny"] as? String,
                    plugins = parseInputPluginConfig(map["plugins"] as? Map<String, Any>),
                )
            }
        }
    }

    private fun parseM2mInputs(m2m: Any?): List<M2mInputConfig> {
        if (m2m == null) return emptyList()
        return (m2m as List<*>).mapNotNull { input ->
            (input as? Map<String, Any>)?.let { map ->
                M2mInputConfig(
                    name = map["name"] as? String ?: return@mapNotNull null,
                    configUrl = map["configUrl"] as? String ?: return@mapNotNull null,
                    refreshIntervalMs = (map["refreshInterval"] as? String)
                        ?.takeIf { it.isNotBlank() && it != "0" }
                        ?.let { Duration(it).millis }
                        ?: 0L,
                    whenCondition = map["when"] as? String,
                    deny = map["deny"] as? String,
                    enabled = map["enabled"] as? Boolean ?: true,
                    insecure = map["insecure"] as? Boolean ?: false,
                    accessControlMode = parseM2mAccessControlMode(map["accessControlMode"] as? String),
                    packages = (map["packages"] as? List<*>)?.mapNotNull { it as? String }
                        ?: emptyList(),
                )
            }
        }
    }

    private fun parseUdp2RawInputs(udp2raw: Any?): List<Udp2RawInputConfig> {
        if (udp2raw == null) return emptyList()
        return (udp2raw as List<*>).mapNotNull { input ->
            (input as? Map<String, Any>)?.let { map ->
                Udp2RawInputConfig(
                    name = map["name"] as? String ?: return@mapNotNull null,
                    dsn = map["dsn"] as? String,
                    args = (map["args"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                    enabled = map["enabled"] as? Boolean ?: true,
                    whenCondition = map["when"] as? String,
                    deny = map["deny"] as? String,
                )
            }
        }
    }

    private fun parseM2mAccessControlMode(mode: String?): M2mAccessControlMode {
        return when (mode?.lowercase()) {
            "include" -> M2mAccessControlMode.include
            "exclude" -> M2mAccessControlMode.exclude
            else -> M2mAccessControlMode.acceptAll
        }
    }

    private fun parseReplay(replay: Any?): ReplayConfig? {
        if (replay == null) return null
        val map = replay as? Map<String, Any> ?: return null
        return ReplayConfig(
            enabled = map["enabled"] as? Boolean ?: false,
            provider = parseReplayProvider(map["provider"] as? String),
            messageIdPath = map["messageIdPath"] as? String ?: "id",
            pageSize = (map["pageSize"] as? Number)?.toInt() ?: 50,
            maxPages = (map["maxPages"] as? Number)?.toInt() ?: 20,
            maxMessages = (map["maxMessages"] as? Number)?.toInt() ?: 500,
            persistState = map["persistState"] as? Boolean ?: true,
            baseUrl = map["baseUrl"] as? String,
            token = map["token"] as? String,
            applicationId = (map["applicationId"] as? Number)?.toInt(),
        )
    }

    private fun parseReplayProvider(provider: String?): ReplayProvider {
        return when (provider?.lowercase()) {
            "gotifyapi", "gotify_api", "gotify-api" -> ReplayProvider.gotifyApi
            else -> ReplayProvider.gotifyApi
        }
    }
}
