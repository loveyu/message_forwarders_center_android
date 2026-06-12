package info.loveyu.mfca.ui

import android.content.Context
import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.input.InputManager
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.NetworkChecker

internal fun getConfiguredLinkStatuses(context: Context): List<ComponentStatus> {
    val configuredLinks = linkedMapOf<String, LinkConfig>()
    ForwardService.currentConfig?.links.orEmpty().forEach { configuredLinks[it.id] = it }
    LinkManager.getAllLinks().keys.forEach { id ->
        LinkManager.getLinkConfig(id)?.let { configuredLinks.putIfAbsent(id, it) }
    }
    LinkManager.getHttpLinkConfigs().forEach { (id, config) ->
        configuredLinks.putIfAbsent(id, config)
    }
    return configuredLinks.values.map { config ->
        if (LinkType.fromDsn(config.dsn) == LinkType.http) {
            buildHttpLinkStatus(context, config)
        } else {
            buildLinkStatus(context, config)
        }
    }
}

internal fun buildLinkStatus(context: Context, config: LinkConfig): ComponentStatus {
    val enableResult = NetworkChecker.getEnableReason(context, config.whenCondition, config.deny)
    val link = LinkManager.getLink(config.id)
    val isConnected = link?.isConnected() ?: false
    val details = buildString {
        val connDetails = link?.getConnectionDetails().orEmpty()
        val protocol = connDetails["protocol"] ?: getLinkTypeString(config)
        append("Type: $protocol")
        connDetails["host"]?.let { append("\nHost: $it") }
        connDetails["port"]?.let { append(":$it") }
        connDetails["resolved_ip"]?.let { append("\nDNS: $it") }
        if (!connDetails.containsKey("host")) {
            config.host?.let { append("\nHost: $it") }
            config.port?.let { append(":$it") }
        }
        if (!connDetails.containsKey("host") && connDetails.isEmpty()) {
            config.dsn?.let { append("\nDSN: $it") }
        }
        if (config.whenCondition != null || config.deny != null) {
            append(
                "\n\n${NetworkChecker.getMatchedConditions(context, config.whenCondition, config.deny)}"
            )
        }
        val tlsInfo = link?.getTlsInfo()
        if (tlsInfo != null) {
            append("\n\nTLS Info:")
            tlsInfo.protocol?.let { append("\n  Protocol: $it") }
            tlsInfo.cipherSuite?.let { append("\n  Cipher: $it") }
            if (tlsInfo.peerCertificates.isNotEmpty()) {
                val cert = tlsInfo.peerCertificates.first()
                append("\n  Certificate:")
                append("\n    Subject: ${cert.subject}")
                append("\n    Issuer: ${cert.issuer}")
                append("\n    Valid: ${cert.validFrom} - ${cert.validTo}")
                cert.serialNumber?.let { append("\n    Serial: $it") }
                cert.fingerprintSha256?.let { append("\n    SHA-256: $it") }
            }
        }
    }
    return ComponentStatus(
        id = config.id,
        name = config.id,
        type = ComponentType.LINK,
        isEnabled = enableResult.enabled,
        isRunning = isConnected,
        notEnabledReason = enableResult.reason,
        details = details
    )
}

internal fun buildHttpLinkStatus(context: Context, config: LinkConfig): ComponentStatus {
    val enableResult = NetworkChecker.getEnableReason(context, config.whenCondition, config.deny)
    val isRunning = InputManager.getSharedHttpInputState(config.id)
    val serverError = InputManager.getSharedHttpInputError(config.id)
    val accessInfo = parseHttpAccessInfo(config.dsn, emptyList())
    val details = buildString {
        append("Type: ${getLinkTypeString(config)}")
        appendHttpAccessDetails(accessInfo)
        if (config.whenCondition != null || config.deny != null) {
            append(
                "\n\n${NetworkChecker.getMatchedConditions(context, config.whenCondition, config.deny)}"
            )
        }
    }
    return ComponentStatus(
        id = config.id,
        name = config.id,
        type = ComponentType.LINK,
        isEnabled = enableResult.enabled,
        isRunning = isRunning,
        notEnabledReason = enableResult.reason,
        details = details,
        error = serverError,
        copyableLinks = accessInfo.accessUrls
    )
}
