package info.loveyu.mfca.ui

import android.content.Context
import info.loveyu.mfca.config.HttpOutputConfig
import info.loveyu.mfca.config.InternalOutputConfig
import info.loveyu.mfca.config.InternalOutputType
import info.loveyu.mfca.config.LinkOutputConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.NetworkChecker

internal fun buildHttpOutputStatus(
    context: Context,
    config: HttpOutputConfig
): ComponentStatus {
    val enableResult =
        NetworkChecker.getEnableReason(context, config.whenCondition, config.deny)
    val isRunning =
        enableResult.enabled && (OutputManager.getOutput(config.name)?.isAvailable() ?: false)
    val details = buildString {
        append("Type: HTTP")
        append("\nURL: ${config.url}")
        append("\nMethod: ${config.method}")
        config.timeout.let { append("\nTimeout: ${it.value}") }
        config.retry?.let {
            append("\nRetry: max ${it.maxAttempts} × ${it.interval.value}")
        }
        config.queue?.name?.let { append("\nQueue: $it") }
        if (config.whenCondition != null || config.deny != null) {
            append(
                "\n\n${NetworkChecker.getMatchedConditions(context, config.whenCondition, config.deny)}"
            )
        }
    }
    return ComponentStatus(
        id = config.name,
        name = config.name,
        type = ComponentType.OUTPUT,
        isEnabled = enableResult.enabled,
        isRunning = isRunning,
        notEnabledReason = enableResult.reason,
        details = details
    )
}

internal fun buildLinkOutputStatus(
    context: Context,
    config: LinkOutputConfig
): ComponentStatus {
    val outputEnableResult =
        NetworkChecker.getEnableReason(context, config.whenCondition, config.deny)
    val isMultiLink = config.linkIds.size > 1
    val upstreamLinks = config.linkIds.map { linkId ->
        val linkConfig = LinkManager.getLinkConfig(linkId)
        val link = LinkManager.getLink(linkId)
        val isConnected = link?.isConnected() ?: false
        val linkEnableResult = if (linkConfig != null) {
            NetworkChecker.getEnableReason(context, linkConfig.whenCondition, linkConfig.deny)
        } else {
            NetworkChecker.EnableResult(enabled = false, reason = "Link $linkId not found")
        }
        val typeLabel = linkConfig?.let { getLinkTypeString(it) } ?: "Unknown"
        UpstreamLinkStatus(
            linkId = linkId,
            isConnected = isConnected,
            isNetworkEnabled = outputEnableResult.enabled && linkEnableResult.enabled,
            typeLabel = typeLabel
        )
    }
    val isRunning =
        outputEnableResult.enabled && (OutputManager.getOutput(config.name)?.isAvailable() ?: false)
    val anyLinkNetworkEnabled = upstreamLinks.any { it.isNetworkEnabled }
    val isEnabled =
        outputEnableResult.enabled && (anyLinkNetworkEnabled || config.linkIds.isEmpty())
    val notEnabledReason = when {
        !outputEnableResult.enabled -> outputEnableResult.reason
        !anyLinkNetworkEnabled && config.linkIds.isNotEmpty() -> {
            if (config.linkIds.size == 1) {
                val linkId = config.linkIds.first()
                val linkConfig = LinkManager.getLinkConfig(linkId)
                if (linkConfig != null) {
                    NetworkChecker.getEnableReason(
                        context,
                        linkConfig.whenCondition,
                        linkConfig.deny
                    ).reason
                } else {
                    "Link $linkId not found"
                }
            } else {
                "All upstream links disabled by network conditions"
            }
        }
        else -> null
    }
    val typeStr = if (isMultiLink) {
        "Fan-out (${config.linkIds.size} links)"
    } else {
        val linkType = LinkManager.getLinkConfig(config.linkId)?.dsn?.let { LinkType.fromDsn(it) }
        when (linkType) {
            LinkType.mqtt -> "MQTT"
            LinkType.websocket -> "WebSocket"
            LinkType.tcp -> "TCP"
            else -> "Link"
        }
    }
    val details = buildString {
        append("Type: $typeStr Output")
        if (isMultiLink) {
            append("\nLinks: ${config.linkIds.joinToString()}")
        } else {
            append("\nLink: ${config.linkId}")
        }
        append("\nRole: ${config.role}")
        config.topic?.let { append("\nTopic: $it") }
        config.retry?.let {
            append("\nRetry: max ${it.maxAttempts} × ${it.interval.value}")
        }
        config.queue?.name?.let { append("\nQueue: $it") }
        if (config.whenCondition != null || config.deny != null) {
            append(
                "\n\n${NetworkChecker.getMatchedConditions(context, config.whenCondition, config.deny)}"
            )
        }
    }
    return ComponentStatus(
        id = config.name,
        name = config.name,
        type = ComponentType.OUTPUT,
        isEnabled = isEnabled,
        isRunning = isRunning,
        notEnabledReason = notEnabledReason,
        details = details,
        upstreamLinks = upstreamLinks
    )
}

internal fun buildInternalOutputStatus(
    context: Context,
    config: InternalOutputConfig
): ComponentStatus {
    val enableResult =
        NetworkChecker.getEnableReason(context, config.whenCondition, config.deny)
    val isRunning =
        enableResult.enabled && (OutputManager.getOutput(config.name)?.isAvailable() ?: false)
    val typeStr = when (config.type) {
        InternalOutputType.clipboard -> "Clipboard"
        InternalOutputType.file -> "File"
        InternalOutputType.broadcast -> "Broadcast"
        InternalOutputType.notify -> "Notify"
        InternalOutputType.clipboardHistory -> "Clipboard History"
    }
    val details = buildString {
        append("Type: $typeStr")
        config.basePath?.let { append("\nPath: $it") }
        config.fileName?.let { append("\nFile: $it") }
        config.channel?.let { append("\nChannel: $it") }
        if (config.whenCondition != null || config.deny != null) {
            append(
                "\n\n${NetworkChecker.getMatchedConditions(context, config.whenCondition, config.deny)}"
            )
        }
    }
    return ComponentStatus(
        id = config.name,
        name = config.name,
        type = ComponentType.OUTPUT,
        isEnabled = enableResult.enabled,
        isRunning = isRunning,
        notEnabledReason = enableResult.reason,
        details = details
    )
}
