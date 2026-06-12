package info.loveyu.mfca.ui

import android.content.Context
import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.config.HttpInputConfig
import info.loveyu.mfca.config.HttpInputDsnParser
import info.loveyu.mfca.config.HttpInputParsedConfig
import info.loveyu.mfca.config.HttpOutputConfig
import info.loveyu.mfca.config.InternalOutputConfig
import info.loveyu.mfca.config.InternalOutputType
import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.config.LinkInputConfig
import info.loveyu.mfca.config.LinkOutputConfig
import info.loveyu.mfca.config.LinkType
import info.loveyu.mfca.config.Udp2RawInputConfig
import info.loveyu.mfca.input.HttpInput
import info.loveyu.mfca.input.HttpVirtualInput
import info.loveyu.mfca.input.InputManager
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.NetworkChecker
import java.net.URI

private val forwardServiceCurrentConfig: AppConfig?
    get() = ForwardService.currentConfig

private fun getLinkTypeString(config: LinkConfig): String {
    return when {
        config.dsn?.startsWith("mqtts") == true -> "MQTT (SSL)"
        config.dsn?.startsWith("mqtt") == true -> "MQTT"
        config.dsn?.startsWith("wss") == true -> "WebSocket (SSL)"
        config.dsn?.startsWith("ws") == true -> "WebSocket"
        config.dsn?.startsWith("ssl") == true -> "TCP (SSL)"
        config.dsn?.startsWith("tcp") == true -> "TCP"
        config.dsn?.startsWith("https") == true -> "HTTPS"
        config.dsn?.startsWith("http") == true -> "HTTP"
        config.dsn?.startsWith("wss") == true -> "WebSocket (SSL)"
        config.dsn?.startsWith("ws") == true -> "WebSocket"
        else -> "Unknown"
    }
}

private data class HttpAccessInfo(
    val scheme: String,
    val listenHost: String,
    val port: Int,
    val accessUrls: List<String>
)

private fun isWildcardHost(host: String): Boolean {
    return host == "0.0.0.0" || host == "::" || host == "*"
}

private fun normalizeHttpPath(path: String): String {
    if (path.isBlank()) return ""
    if (path == "/") return "/"
    return if (path.startsWith("/")) path else "/$path"
}

private fun resolveHttpHosts(listenHost: String): List<String> {
    val normalizedHost = listenHost.removePrefix("[").removeSuffix("]").trim()
    return when {
        normalizedHost.isEmpty() -> listOf("0.0.0.0")
        isWildcardHost(normalizedHost) ->
            NetworkChecker.getAllLocalIpv4Addresses().ifEmpty { listOf(normalizedHost) }
        normalizedHost.equals("localhost", ignoreCase = true) -> listOf("127.0.0.1")
        else -> listOf(normalizedHost)
    }
}

private fun buildHttpAccessUrls(
    scheme: String,
    listenHost: String,
    port: Int,
    paths: List<String>
): List<String> {
    val normalizedPaths = if (paths.isEmpty()) listOf("") else paths.map(::normalizeHttpPath)
    return resolveHttpHosts(listenHost)
        .flatMap { host ->
            normalizedPaths.map { path -> "$scheme://$host:$port$path" }
        }
        .distinct()
}

private fun parseHttpAccessInfo(dsn: String?, paths: List<String>): HttpAccessInfo {
    val fallbackScheme = "http"
    val fallbackHost = "0.0.0.0"
    val (scheme, host, port) = try {
        val uri = URI(dsn ?: "")
        val resolvedScheme = uri.scheme?.lowercase() ?: fallbackScheme
        Triple(
            resolvedScheme,
            uri.host ?: fallbackHost,
            if (uri.port > 0) uri.port else if (resolvedScheme == "https") 443 else 8080
        )
    } catch (_: Exception) {
        Triple(fallbackScheme, fallbackHost, 8080)
    }
    return HttpAccessInfo(
        scheme = scheme,
        listenHost = host,
        port = port,
        accessUrls = buildHttpAccessUrls(scheme, host, port, paths)
    )
}

private fun parseHttpInputConfigSafely(httpConfig: HttpInputConfig): HttpInputParsedConfig? {
    return try {
        HttpInputDsnParser.parse(httpConfig.dsn)
    } catch (_: Exception) {
        null
    }
}

private fun StringBuilder.appendHttpAccessDetails(accessInfo: HttpAccessInfo) {
    if (accessInfo.accessUrls.isNotEmpty()) {
        append("\nAddresses:")
        accessInfo.accessUrls.forEach { append("\n$it") }
    } else {
        append("\nListen: ${accessInfo.listenHost}:${accessInfo.port}")
    }
}

private fun formatPipelineStep(step: info.loveyu.mfca.config.PipelineStep): String {
    val lines = mutableListOf<String>()
    step.transform?.extract?.let { lines.add("Extract: $it") }
    step.transform?.filter?.let { lines.add("Filter: $it") }
    step.transform?.detect?.let { lines.add("Detect: $it") }
    step.transform?.format?.let { lines.add("Format: $it") }
    step.transform?.enrich?.let { lines.add("Enrich: $it") }
    if (step.to.isNotEmpty()) {
        lines.add("To: ${step.to.joinToString()}")
    }
    return if (lines.isEmpty()) "No transform" else lines.joinToString("\n")
}

fun getAllComponentStatuses(context: Context): List<ComponentStatus> {
    val statuses = mutableListOf<ComponentStatus>()
    getConfiguredLinkStatuses(context).forEach(statuses::add)
    forwardServiceCurrentConfig?.let { appConfig ->
        appConfig.inputs.http.forEach { httpConfig ->
            statuses.add(buildHttpInputStatus(context, appConfig, httpConfig))
        }
        appConfig.inputs.link.forEach { linkInputConfig ->
            val targetLinkIds = if (linkInputConfig.linkIds.isNotEmpty()) {
                linkInputConfig.linkIds
            } else {
                listOf(linkInputConfig.linkId)
            }
            targetLinkIds.forEach { linkId ->
                statuses.add(
                    buildLinkInputStatus(
                        context = context,
                        config = linkInputConfig,
                        linkId = linkId,
                        includeLinkIdInName = targetLinkIds.size > 1
                    )
                )
            }
        }
        appConfig.inputs.udp2raw.forEach { udp2rawConfig ->
            statuses.add(buildUdp2RawInputStatus(context, udp2rawConfig))
        }
        appConfig.rules.forEach { ruleConfig ->
            statuses.add(buildRuleStatus(context, ruleConfig))
        }
        appConfig.outputs.http.forEach { httpOutputConfig ->
            statuses.add(buildHttpOutputStatus(context, httpOutputConfig))
        }
        appConfig.outputs.link.forEach { linkOutputConfig ->
            statuses.add(buildLinkOutputStatus(context, linkOutputConfig))
        }
        appConfig.outputs.internal.forEach { internalOutputConfig ->
            statuses.add(buildInternalOutputStatus(context, internalOutputConfig))
        }
    }
    info.loveyu.mfca.queue.QueueManager.getAllQueues().forEach { (_, queue) ->
        statuses.add(buildQueueStatus(queue))
    }
    return statuses
}

private fun getConfiguredLinkStatuses(context: Context): List<ComponentStatus> {
    val configuredLinks = linkedMapOf<String, LinkConfig>()
    forwardServiceCurrentConfig?.links.orEmpty().forEach { configuredLinks[it.id] = it }
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

private fun buildLinkStatus(context: Context, config: LinkConfig): ComponentStatus {
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

private fun buildHttpLinkStatus(context: Context, config: LinkConfig): ComponentStatus {
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

private fun buildHttpInputStatus(
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

private fun buildLinkInputStatus(
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

private fun buildUdp2RawInputStatus(
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

private fun buildRuleStatus(
    context: Context,
    config: info.loveyu.mfca.config.RuleConfig
): ComponentStatus {
    val enableResult =
        NetworkChecker.getEnableReason(context, config.whenCondition, config.deny)
    val sources = if (config.froms.isNotEmpty()) config.froms else listOf(config.from)
    val outputs = config.pipeline.flatMap { it.to }.distinct()
    val errorOutputs = config.onError.orEmpty().flatMap { it.to }.distinct()
    val details = buildString {
        append("From: ${sources.joinToString()}")
        append("\nSteps: ${config.pipeline.size}")
        if (outputs.isNotEmpty()) {
            append("\nOutputs: ${outputs.joinToString()}")
        }
        config.pipeline.forEachIndexed { index, step ->
            append("\n\nStep ${index + 1}:")
            append("\n${formatPipelineStep(step)}")
        }
        if (config.onError != null) {
            append("\n\nOn Error Steps: ${config.onError.size}")
            if (errorOutputs.isNotEmpty()) {
                append("\nOn Error Outputs: ${errorOutputs.joinToString()}")
            }
            config.onError.forEachIndexed { index, step ->
                append("\n\nOn Error ${index + 1}:")
                append("\n${formatPipelineStep(step)}")
            }
        }
        if (config.whenCondition != null || config.deny != null) {
            append(
                "\n\n${NetworkChecker.getMatchedConditions(context, config.whenCondition, config.deny)}"
            )
        }
    }
    return ComponentStatus(
        id = config.name,
        name = config.name,
        type = ComponentType.RULE,
        isEnabled = enableResult.enabled,
        isRunning = ForwardService.isRunning && enableResult.enabled,
        notEnabledReason = enableResult.reason,
        details = details
    )
}

private fun buildHttpOutputStatus(
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

private fun buildLinkOutputStatus(
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

private fun buildInternalOutputStatus(
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

private fun buildQueueStatus(queue: info.loveyu.mfca.queue.Queue): ComponentStatus {
    val typeStr = when (queue.type) {
        info.loveyu.mfca.queue.QueueType.memory -> "Memory"
        info.loveyu.mfca.queue.QueueType.sqlite -> "SQLite"
    }
    val size = try {
        queue.size()
    } catch (_: Exception) {
        -1
    }
    val details = buildString {
        append("Type: $typeStr Queue")
        if (size >= 0) append("\nPending: $size item(s)")
        else append("\nSize: N/A")
    }
    return ComponentStatus(
        id = "queue:${queue.name}",
        name = queue.name,
        type = ComponentType.QUEUE,
        isEnabled = true,
        isRunning = true,
        details = details
    )
}

fun getGroupedComponentStatuses(context: Context): List<Pair<ComponentType, List<ComponentStatus>>> {
    return getAllComponentStatuses(context)
        .groupBy { it.type }
        .toList()
        .sortedBy { getComponentTypeOrder(it.first) }
}

fun getEnabledAndDisabledComponents(
    context: Context
): Pair<List<ComponentStatus>, List<ComponentStatus>> {
    val all = getAllComponentStatuses(context)
    val enabled = all.filter { it.isEnabled }
    val disabled = all.filter { !it.isEnabled }
    return Pair(enabled, disabled)
}
