package info.loveyu.mfca.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.filled.Send
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
import info.loveyu.mfca.input.HttpInput
import info.loveyu.mfca.input.HttpVirtualInput
import info.loveyu.mfca.input.InputManager
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.ui.theme.OutputChipBgDark
import info.loveyu.mfca.ui.theme.OutputChipBgLight
import info.loveyu.mfca.ui.theme.OutputChipBorderDark
import info.loveyu.mfca.ui.theme.OutputChipBorderLight
import info.loveyu.mfca.ui.theme.DisabledChipBgDark
import info.loveyu.mfca.ui.theme.DisabledChipBgLight
import info.loveyu.mfca.ui.theme.DisabledChipBorderDark
import info.loveyu.mfca.ui.theme.DisabledChipBorderLight
import info.loveyu.mfca.ui.theme.ErrorCardBgDark
import info.loveyu.mfca.ui.theme.ErrorCardBgLight
import info.loveyu.mfca.ui.theme.ErrorCardIconDark
import info.loveyu.mfca.ui.theme.ErrorCardIconLight
import info.loveyu.mfca.ui.theme.HttpInputChipBgDark
import info.loveyu.mfca.ui.theme.HttpInputChipBgLight
import info.loveyu.mfca.ui.theme.HttpInputChipBorderDark
import info.loveyu.mfca.ui.theme.HttpInputChipBorderLight
import info.loveyu.mfca.ui.theme.LinkChipBgDark
import info.loveyu.mfca.ui.theme.LinkChipBgLight
import info.loveyu.mfca.ui.theme.LinkChipBorderDark
import info.loveyu.mfca.ui.theme.LinkChipBorderLight
import info.loveyu.mfca.ui.theme.LinkInputChipBgDark
import info.loveyu.mfca.ui.theme.LinkInputChipBgLight
import info.loveyu.mfca.ui.theme.LinkInputChipBorderDark
import info.loveyu.mfca.ui.theme.LinkInputChipBorderLight
import info.loveyu.mfca.ui.theme.StatusDisabledDark
import info.loveyu.mfca.ui.theme.StatusDisabledLight
import info.loveyu.mfca.ui.theme.StatusErrorDark
import info.loveyu.mfca.ui.theme.StatusErrorLight
import info.loveyu.mfca.ui.theme.StatusRunningDark
import info.loveyu.mfca.ui.theme.StatusRunningLight
import info.loveyu.mfca.ui.theme.StatusWarningDark
import info.loveyu.mfca.ui.theme.StatusWarningLight
import info.loveyu.mfca.ui.theme.WarningCardBgDark
import info.loveyu.mfca.ui.theme.WarningCardBgLight
import info.loveyu.mfca.ui.theme.WarningCardIconDark
import info.loveyu.mfca.ui.theme.WarningCardIconLight
import info.loveyu.mfca.util.NetworkChecker
import java.net.URI

/**
 * 组件类型
 */
enum class ComponentType {
    LINK, HTTP_INPUT, LINK_INPUT, RULE, OUTPUT
}

/**
 * 组件状态信息
 */
data class ComponentStatus(
    val id: String,
    val name: String,
    val type: ComponentType,
    val isEnabled: Boolean, // 符合网络运行条件
    val isRunning: Boolean, // 实际运行/连接状态
    val notEnabledReason: String? = null, // 不符合条件的原因
    val details: String = "", // 详细信息
    val error: String? = null, // 启动错误(如端口冲突)
    val copyableLinks: List<String> = emptyList()
) {
    val isLink: Boolean get() = type == ComponentType.LINK
    val isInput: Boolean get() = type == ComponentType.HTTP_INPUT || type == ComponentType.LINK_INPUT
}

/**
 * 获取所有组件状态
 */
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
        appConfig.rules.forEach { ruleConfig ->
            statuses.add(buildRuleStatus(context, ruleConfig))
        }
        appConfig.outputs.http.forEach { httpOutputConfig ->
            statuses.add(buildHttpOutputStatus(httpOutputConfig))
        }
        appConfig.outputs.link.forEach { linkOutputConfig ->
            statuses.add(buildLinkOutputStatus(context, linkOutputConfig))
        }
        appConfig.outputs.internal.forEach { internalOutputConfig ->
            statuses.add(buildInternalOutputStatus(internalOutputConfig))
        }
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
            append("\n\n${NetworkChecker.getMatchedConditions(context, config.whenCondition, config.deny)}")
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
            append("\n\n${NetworkChecker.getMatchedConditions(context, config.whenCondition, config.deny)}")
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
    appConfig: info.loveyu.mfca.config.AppConfig,
    httpConfig: HttpInputConfig
): ComponentStatus {
    val enableResult = NetworkChecker.getEnableReason(context, httpConfig.whenCondition, httpConfig.deny)
    val input = InputManager.getInput(httpConfig.name, httpConfig.linkId)
    val parsedConfig = when (input) {
        is HttpInput -> input.getParsedConfig()
        is HttpVirtualInput -> input.getParsedConfig()
        else -> parseHttpInputConfigSafely(httpConfig)
    }
    val sharedServerError = httpConfig.linkId?.let(InputManager::getSharedHttpInputError)
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
        append("\nPaths: ${if (httpConfig.paths.isNotEmpty()) httpConfig.paths.joinToString() else "*"}")
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
            append("\n\n${NetworkChecker.getMatchedConditions(context, httpConfig.whenCondition, httpConfig.deny)}")
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
    val inputEnableResult = NetworkChecker.getEnableReason(context, config.whenCondition, config.deny)
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
        config.excludeTopics?.takeIf { it.isNotEmpty() }?.let { append("\nExclude: ${it.joinToString()}") }
        if (!linkConnected) {
            append("\n⚠ Link not connected")
        }
        if (config.whenCondition != null || config.deny != null) {
            append("\n\nInput Conditions:")
            append("\n${NetworkChecker.getMatchedConditions(context, config.whenCondition, config.deny)}")
        }
        if (linkConfig?.whenCondition != null || linkConfig?.deny != null) {
            append("\n\nLink Conditions:")
            append("\n${NetworkChecker.getMatchedConditions(context, linkConfig?.whenCondition, linkConfig?.deny)}")
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

private fun buildRuleStatus(
    context: Context,
    config: info.loveyu.mfca.config.RuleConfig
): ComponentStatus {
    val enableResult = NetworkChecker.getEnableReason(context, config.whenCondition, config.deny)
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
            append("\n\n${NetworkChecker.getMatchedConditions(context, config.whenCondition, config.deny)}")
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

private data class HttpAccessInfo(
    val scheme: String,
    val listenHost: String,
    val port: Int,
    val accessUrls: List<String>
)

private fun parseHttpInputConfigSafely(httpConfig: HttpInputConfig): HttpInputParsedConfig? {
    return try {
        HttpInputDsnParser.parse(httpConfig.dsn)
    } catch (_: Exception) {
        null
    }
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

private fun resolveHttpHosts(listenHost: String): List<String> {
    val normalizedHost = listenHost.removePrefix("[").removeSuffix("]").trim()
    return when {
        normalizedHost.isEmpty() -> listOf("0.0.0.0")
        isWildcardHost(normalizedHost) -> NetworkChecker.getAllLocalIpv4Addresses().ifEmpty { listOf(normalizedHost) }
        normalizedHost.equals("localhost", ignoreCase = true) -> listOf("127.0.0.1")
        else -> listOf(normalizedHost)
    }
}

private fun normalizeHttpPath(path: String): String {
    if (path.isBlank()) return ""
    if (path == "/") return "/"
    return if (path.startsWith("/")) path else "/$path"
}

private fun StringBuilder.appendHttpAccessDetails(accessInfo: HttpAccessInfo) {
    if (accessInfo.accessUrls.isNotEmpty()) {
        append("\nAddresses:")
        accessInfo.accessUrls.forEach { append("\n$it") }
    } else {
        append("\nListen: ${accessInfo.listenHost}:${accessInfo.port}")
    }
}

private fun isWildcardHost(host: String): Boolean {
    return host == "0.0.0.0" || host == "::" || host == "*"
}

private fun buildHttpOutputStatus(config: HttpOutputConfig): ComponentStatus {
    val isRunning = OutputManager.getOutput(config.name)?.isAvailable() ?: false
    val details = buildString {
        append("Type: HTTP")
        append("\nURL: ${config.url}")
        append("\nMethod: ${config.method}")
        config.timeout.let { append("\nTimeout: ${it.value}") }
        config.retry?.let { append("\nRetry: max ${it.maxAttempts} × ${it.interval.value}") }
        config.queue?.memoryQueue?.let { append("\nQueue: memory/$it") }
        config.queue?.sqliteQueue?.let { append("\nQueue: sqlite/$it") }
    }
    return ComponentStatus(
        id = config.name,
        name = config.name,
        type = ComponentType.OUTPUT,
        isEnabled = true,
        isRunning = isRunning,
        details = details
    )
}

private fun buildLinkOutputStatus(context: Context, config: LinkOutputConfig): ComponentStatus {
    val enableResult = NetworkChecker.getEnableReason(context, config.whenCondition, config.deny)
    val isRunning =
        enableResult.enabled && (OutputManager.getOutput(config.name)?.isAvailable() ?: false)
    val linkType = LinkManager.getLinkConfig(config.linkId)?.dsn?.let { LinkType.fromDsn(it) }
    val typeStr =
        when (linkType) {
            LinkType.mqtt -> "MQTT"
            LinkType.websocket -> "WebSocket"
            LinkType.tcp -> "TCP"
            else -> "Link"
        }
    val details = buildString {
        append("Type: $typeStr Output")
        append("\nLink: ${config.linkId}")
        append("\nRole: ${config.role}")
        config.topic?.let { append("\nTopic: $it") }
        config.queue?.memoryQueue?.let { append("\nQueue: memory/$it") }
        config.queue?.sqliteQueue?.let { append("\nQueue: sqlite/$it") }
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

private fun buildInternalOutputStatus(config: InternalOutputConfig): ComponentStatus {
    val isRunning = OutputManager.getOutput(config.name)?.isAvailable() ?: false
    val typeStr =
        when (config.type) {
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
    }
    return ComponentStatus(
        id = config.name,
        name = config.name,
        type = ComponentType.OUTPUT,
        isEnabled = true,
        isRunning = isRunning,
        details = details
    )
}

fun getGroupedComponentStatuses(context: Context): List<Pair<ComponentType, List<ComponentStatus>>> {
    return getAllComponentStatuses(context)
        .groupBy { it.type }
        .toList()
        .sortedBy { getComponentTypeOrder(it.first) }
}

fun getComponentTypeOrder(type: ComponentType): Int {
    return when (type) {
        ComponentType.LINK -> 0
        ComponentType.HTTP_INPUT -> 1
        ComponentType.LINK_INPUT -> 2
        ComponentType.RULE -> 3
        ComponentType.OUTPUT -> 4
    }
}

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

/**
 * 获取符合和不符合条件的组件列表
 */
fun getEnabledAndDisabledComponents(context: Context): Pair<List<ComponentStatus>, List<ComponentStatus>> {
    val all = getAllComponentStatuses(context)
    val enabled = all.filter { it.isEnabled }
    val disabled = all.filter { !it.isEnabled }
    return Pair(enabled, disabled)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentStatusSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val networkStateVersion by LinkManager.networkStateVersion.collectAsState()

    LaunchedEffect(Unit) {
        LinkManager.refreshNetworkState()
    }

    val (enabledComponents, disabledComponents) = remember(
        networkStateVersion,
        ForwardService.isRunning,
        ForwardService.currentConfig
    ) {
        getEnabledAndDisabledComponents(context)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "组件状态",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Enabled components (can scroll horizontally)
            if (enabledComponents.isNotEmpty()) {
                Text(
                    text = "已启用 (${enabledComponents.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    enabledComponents.forEach { component ->
                        ComponentCard(
                            component = component,
                            isEnabled = true
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Disabled components
            if (disabledComponents.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "未启用 (${disabledComponents.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    disabledComponents.forEach { component ->
                        ComponentCard(
                            component = component,
                            isEnabled = false
                        )
                    }
                }
            }

            if (enabledComponents.isEmpty() && disabledComponents.isEmpty()) {
                Text(
                    text = "暂无组件配置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ComponentCard(
    component: ComponentStatus,
    isEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val backgroundColor = if (isEnabled) {
        when (component.type) {
            ComponentType.LINK -> if (isDark) LinkChipBgDark else LinkChipBgLight
            ComponentType.HTTP_INPUT -> if (isDark) HttpInputChipBgDark else HttpInputChipBgLight
            ComponentType.LINK_INPUT -> if (isDark) LinkInputChipBgDark else LinkInputChipBgLight
            ComponentType.RULE -> if (isDark) LinkInputChipBgDark else LinkInputChipBgLight
            ComponentType.OUTPUT -> if (isDark) OutputChipBgDark else OutputChipBgLight
        }
    } else {
        if (isDark) DisabledChipBgDark else DisabledChipBgLight
    }

    val borderColor = if (isEnabled) {
        when (component.type) {
            ComponentType.LINK -> if (isDark) LinkChipBorderDark else LinkChipBorderLight
            ComponentType.HTTP_INPUT -> if (isDark) HttpInputChipBorderDark else HttpInputChipBorderLight
            ComponentType.LINK_INPUT -> if (isDark) LinkInputChipBorderDark else LinkInputChipBorderLight
            ComponentType.RULE -> if (isDark) LinkInputChipBorderDark else LinkInputChipBorderLight
            ComponentType.OUTPUT -> if (isDark) OutputChipBorderDark else OutputChipBorderLight
        }
    } else {
        if (isDark) DisabledChipBorderDark else DisabledChipBorderLight
    }

    val statusDotColor = when {
        component.error != null -> if (isDark) StatusErrorDark else StatusErrorLight
        component.isRunning -> if (isDark) StatusRunningDark else StatusRunningLight
        isEnabled -> if (isDark) StatusWarningDark else StatusWarningLight
        else -> if (isDark) StatusDisabledDark else StatusDisabledLight
    }

    Card(
        modifier = modifier
            .width(140.dp)
            .clickable { /* Will be handled by sheet */ },
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status indicator
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusDotColor)
                )
                Spacer(modifier = Modifier.width(8.dp))

                // Component type icon
                Icon(
                    imageVector = getComponentIcon(component.type),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = borderColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = component.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = getComponentTypeName(component.type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Running status
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (component.error != null) Icons.Default.Warning
                        else if (component.isRunning) Icons.Default.CheckCircle
                        else Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = when {
                        component.error != null -> if (isDark) StatusErrorDark else StatusErrorLight
                        component.isRunning -> if (isDark) StatusRunningDark else StatusRunningLight
                        else -> if (isDark) StatusDisabledDark else StatusDisabledLight
                    }
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = when {
                        component.error != null -> "Error"
                        component.isRunning -> "Running"
                        else -> "Stopped"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        component.error != null -> if (isDark) StatusErrorDark else StatusErrorLight
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentDetailSheet(
    component: ComponentStatus?,
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
    if (component == null) return

    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val context = LocalContext.current

    LaunchedEffect(component.id, component.type) {
        LinkManager.refreshNetworkState()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = getComponentIcon(component.type),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = component.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = getComponentTypeName(component.type),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Status section
            Text(
                text = "状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusItem(
                    label = "网络条件",
                    value = if (component.isEnabled) "符合" else "不符合",
                    valueColor = if (component.isEnabled) {
                        if (isDark) StatusRunningDark else StatusRunningLight
                    } else {
                        if (isDark) StatusErrorDark else StatusErrorLight
                    }
                )
                StatusItem(
                    label = "运行状态",
                    value = when {
                        component.error != null -> "错误"
                        component.isRunning -> "运行中"
                        else -> "已停止"
                    },
                    valueColor = when {
                        component.error != null -> if (isDark) StatusErrorDark else StatusErrorLight
                        component.isRunning -> if (isDark) StatusRunningDark else StatusRunningLight
                        else -> if (isDark) StatusDisabledDark else StatusDisabledLight
                    }
                )
            }

            // Not enabled reason
            if (!component.isEnabled && component.notEnabledReason != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDark) WarningCardBgDark else WarningCardBgLight
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (isDark) WarningCardIconDark else WarningCardIconLight
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "不符合条件原因",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = component.notEnabledReason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Error state (e.g. port conflict, DSN parse failure)
            if (component.error != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDark) ErrorCardBgDark else ErrorCardBgLight
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (isDark) ErrorCardIconDark else ErrorCardIconLight
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "启动错误",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = component.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isDark) ErrorCardIconDark else ErrorCardIconLight
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            if (component.copyableLinks.isNotEmpty()) {
                Text(
                    text = "链接地址",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))

                component.copyableLinks.forEach { link ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = link,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = {
                            copyPlainText(context, "HTTP 地址", link)
                            Toast.makeText(context, "已复制地址", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("复制")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Details section
            Text(
                text = "详细信息",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = component.details.ifEmpty { "无详细信息" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun copyPlainText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
private fun StatusItem(
    label: String,
    value: String,
    valueColor: Color
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(valueColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = valueColor
            )
        }
    }
}

private fun getComponentIcon(type: ComponentType): ImageVector {
    return when (type) {
        ComponentType.LINK -> Icons.Default.Star
        ComponentType.HTTP_INPUT -> Icons.Default.PlayArrow
        ComponentType.LINK_INPUT -> Icons.Default.PlayArrow
        ComponentType.RULE -> Icons.Default.Build
        ComponentType.OUTPUT -> Icons.AutoMirrored.Filled.Send
    }
}

fun getComponentTypeName(type: ComponentType): String {
    return when (type) {
        ComponentType.LINK -> "Link"
        ComponentType.HTTP_INPUT -> "HTTP Input"
        ComponentType.LINK_INPUT -> "Link Input"
        ComponentType.RULE -> "Rule"
        ComponentType.OUTPUT -> "Output"
    }
}

// Access ForwardService.currentConfig
private val forwardServiceCurrentConfig: info.loveyu.mfca.config.AppConfig?
    get() = info.loveyu.mfca.service.ForwardService.currentConfig
