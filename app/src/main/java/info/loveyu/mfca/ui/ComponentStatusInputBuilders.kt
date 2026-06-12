package info.loveyu.mfca.ui

import android.content.Context
import info.loveyu.mfca.config.models.AppConfig
import info.loveyu.mfca.config.models.HttpInputConfig
import info.loveyu.mfca.config.models.LinkInputConfig
import info.loveyu.mfca.config.models.Udp2RawInputConfig
import info.loveyu.mfca.input.HttpInput
import info.loveyu.mfca.input.HttpVirtualInput
import info.loveyu.mfca.input.InputManager
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.util.NetworkChecker

internal fun buildHttpInputStatus(
    context: Context,
    appConfig: AppConfig,
    httpConfig: HttpInputConfig
): ComponentStatus {
    val enableResult =
        NetworkChecker.getEnableReason(context, httpConfig.whenCondition, httpConfig.deny)
    val input = InputManager.getInput(httpConfig.name, httpConfig.linkId)
    val parsedConfig = when (input) {
        is HttpInput -> input.getParsedConfig()
        is HttpVirtualInput -> input.getParsedConfig()
        else -> parseHttpInputConfigSafely(httpConfig)
    }
    val sharedServerError =
        httpConfig.linkId?.let(InputManager::getSharedHttpInputError)
    val errorMessages = listOfNotNull(input?.getError(), sharedServerError).distinct()
    val error = errorMessages.takeIf { it.isNotEmpty() }?.joinToString("\n")
    val isRunning = if (httpConfig.linkId != null) {
        InputManager.getSharedHttpInputState(httpConfig.linkId) && input?.hasFatalError() != true
    } else {
        input?.isRunning() ?: false
    }
    val listenDsn = if (httpConfig.linkId != null) {
        appConfig.links.find { it.id == httpConfig.linkId }?.dsn
    } else {
        httpConfig.dsn
    }
    val accessInfo = parseHttpAccessInfo(listenDsn, httpConfig.paths)
    val details = buildString {
        if (httpConfig.linkId != null) {
            append("Shared Server: ${httpConfig.linkId}")
        } else if (parsedConfig != null) {
            append("Listen Host: ${parsedConfig.listen}")
        }
        appendHttpAccessDetails(accessInfo)
        append(
            "\nPaths: ${if (httpConfig.paths.isNotEmpty()) httpConfig.paths.joinToString() else "*"}"
        )
        if (!parsedConfig?.methods.isNullOrEmpty()) {
            append("\nMethods: ${parsedConfig?.methods?.joinToString()}")
        }
        val authMethods = mutableListOf<String>()
        parsedConfig?.basicAuth?.let { authMethods.add("basic") }
        parsedConfig?.bearerAuth?.let { authMethods.add("bearer") }
        parsedConfig?.queryAuth?.let { authMethods.add("query") }
        parsedConfig?.cookieAuth?.let { authMethods.add("cookie") }
        if (authMethods.isNotEmpty()) {
            append("\nAuth: ${authMethods.joinToString(", ")}")
        }
        if (!parsedConfig?.allowIps.isNullOrEmpty()) {
            append("\nAllow IPs: ${parsedConfig?.allowIps?.joinToString()}")
        }
        if (!parsedConfig?.denyIps.isNullOrEmpty()) {
            append("\nDeny IPs: ${parsedConfig?.denyIps?.joinToString()}")
        }
        if (httpConfig.whenCondition != null || httpConfig.deny != null) {
            append(
                "\n\n${NetworkChecker.getMatchedConditions(context, httpConfig.whenCondition, httpConfig.deny)}"
            )
        }
    }
    return ComponentStatus(
        id = if (httpConfig.linkId != null) "${httpConfig.name}@${httpConfig.linkId}" else httpConfig.name,
        name = httpConfig.name,
        type = ComponentType.HTTP_INPUT,
        isEnabled = enableResult.enabled,
        isRunning = isRunning,
        notEnabledReason = enableResult.reason,
        details = details,
        error = error,
        copyableLinks = accessInfo.accessUrls
    )
}

internal fun buildLinkInputStatus(
    context: Context,
    config: LinkInputConfig,
    linkId: String,
    includeLinkIdInName: Boolean
): ComponentStatus {
    val inputEnableResult =
        NetworkChecker.getEnableReason(context, config.whenCondition, config.deny)
    val linkConfig = LinkManager.getLinkConfig(linkId)
    val linkEnableResult = if (linkConfig != null) {
        NetworkChecker.getEnableReason(context, linkConfig.whenCondition, linkConfig.deny)
    } else {
        NetworkChecker.EnableResult(enabled = false, reason = "Link $linkId not found")
    }
    val input = InputManager.getInput(config.name, linkId)
    val linkConnected = LinkManager.getLink(linkId)?.isConnected() ?: false
    val isEnabled = inputEnableResult.enabled && linkEnableResult.enabled
    val notEnabledReason = when {
        !inputEnableResult.enabled -> inputEnableResult.reason
        !linkEnableResult.enabled -> linkEnableResult.reason
        !linkConnected -> "Link $linkId not connected"
        else -> null
    }
    val details = buildString {
        append("Link: $linkId")
        append("\nRole: ${config.role}")
        config.topic?.let { append("\nTopic: $it") }
        config.topics?.let { append("\nTopics: ${it.joinToString()}") }
        config.excludeTopics?.takeIf { it.isNotEmpty() }?.let {
            append("\nExclude: ${it.joinToString()}")
        }
        if (!linkConnected) {
            append("\n⚠ Link not connected")
        }
        if (config.whenCondition != null || config.deny != null) {
            append("\n\nInput Conditions:")
            append(
                "\n${NetworkChecker.getMatchedConditions(context, config.whenCondition, config.deny)}"
            )
        }
        if (linkConfig?.whenCondition != null || linkConfig?.deny != null) {
            append("\n\nLink Conditions:")
            append(
                "\n${NetworkChecker.getMatchedConditions(context, linkConfig?.whenCondition, linkConfig?.deny)}"
            )
        }
    }
    return ComponentStatus(
        id = "${config.name}@$linkId",
        name = if (includeLinkIdInName) "${config.name} · $linkId" else config.name,
        type = ComponentType.LINK_INPUT,
        isEnabled = isEnabled,
        isRunning = input?.isRunning() ?: false,
        notEnabledReason = notEnabledReason,
        details = details
    )
}

internal fun buildUdp2RawInputStatus(
    context: Context,
    config: Udp2RawInputConfig
): ComponentStatus {
    val input = InputManager.getInput(config.name)
    val networkEnableResult =
        NetworkChecker.getEnableReason(context, config.whenCondition, config.deny)
    val isEnabled = config.enabled && networkEnableResult.enabled
    val notEnabledReason = when {
        !config.enabled -> "Input disabled"
        !networkEnableResult.enabled -> networkEnableResult.reason
        else -> null
    }
    val resolvedRemote =
        (input as? info.loveyu.mfca.input.Udp2RawInput)?.getResolvedRemote()
    val details = buildString {
        append("Type: udp2raw")
        if (config.dsn != null) {
            append("\nDSN: ${config.dsn}")
        }
        if (config.args.isNotEmpty()) {
            append("\nArgs: ${config.args.joinToString(" ")}")
        } else if (config.dsn == null) {
            append("\nArgs: (none)")
        }
        resolvedRemote?.let { append("\nResolved: $it") }
        if (config.whenCondition != null || config.deny != null) {
            append(
                "\n\n${NetworkChecker.getMatchedConditions(context, config.whenCondition, config.deny)}"
            )
        }
    }
    return ComponentStatus(
        id = config.name,
        name = config.name,
        type = ComponentType.UDP2RAW,
        isEnabled = isEnabled,
        isRunning = input?.isRunning() ?: false,
        notEnabledReason = notEnabledReason,
        details = details,
        error = input?.getError()
    )
}
