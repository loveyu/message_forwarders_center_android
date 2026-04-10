package info.loveyu.mfca.ui

import android.content.Context
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.config.LinkConfig
import info.loveyu.mfca.input.HttpInput
import info.loveyu.mfca.input.InputManager
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.util.NetworkChecker

/**
 * 组件类型
 */
enum class ComponentType {
    LINK, HTTP_INPUT, LINK_INPUT
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
    val error: String? = null // 启动错误(如端口冲突)
) {
    val isLink: Boolean get() = type == ComponentType.LINK
    val isInput: Boolean get() = type == ComponentType.HTTP_INPUT || type == ComponentType.LINK_INPUT
}

/**
 * 获取所有组件状态
 */
fun getAllComponentStatuses(context: Context): List<ComponentStatus> {
    val statuses = mutableListOf<ComponentStatus>()

    // Get Link statuses
    LinkManager.getAllLinks().forEach { (id, link) ->
        val config = LinkManager.getLinkConfig(id)
        if (config != null) {
            val enableResult = NetworkChecker.getEnableReason(context, config.whenCondition, config.deny)
            val isConnected = link.isConnected()

            val details = buildString {
                // Connection details from link
                val connDetails = link.getConnectionDetails()
                val protocol = connDetails["protocol"] ?: getLinkTypeString(config)
                append("Type: $protocol")
                connDetails["host"]?.let { append("\nHost: $it") }
                connDetails["port"]?.let { append(":$it") }
                connDetails["resolved_ip"]?.let { append("\nDNS: $it") }

                // Fallback for host/port if getConnectionDetails didn't provide them
                if (!connDetails.containsKey("host")) {
                    config.host?.let { append("\nHost: $it") }
                    config.port?.let { append(":$it") }
                }
                if (!connDetails.containsKey("host") && connDetails.isEmpty()) {
                    config.url?.let { append("\nURL: $it") }
                }

                // Network condition match details
                if (config.whenCondition != null || config.deny != null) {
                    append("\n\n${NetworkChecker.getMatchedConditions(context, config.whenCondition, config.deny)}")
                }

                // TLS certificate info
                val tlsInfo = link.getTlsInfo()
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

            statuses.add(
                ComponentStatus(
                    id = id,
                    name = id,
                    type = ComponentType.LINK,
                    isEnabled = enableResult.enabled,
                    isRunning = isConnected,
                    notEnabledReason = enableResult.reason,
                    details = details
                )
            )
        }
    }

    // Get HTTP Input statuses
    val appConfig = forwardServiceCurrentConfig
    appConfig?.inputs?.http?.forEach { httpConfig ->
        val enableResult = NetworkChecker.getEnableReason(context, httpConfig.whenCondition, httpConfig.deny)
        val input = InputManager.getInput(httpConfig.name)
        val isRunning = input?.isRunning() ?: false
        val inputError = input?.getError()
        val parsedConfig = (input as? HttpInput)?.getParsedConfig()

        val details = buildString {
            append("Listen: ${parsedConfig?.listen}:${parsedConfig?.port}")
            if (httpConfig.paths.isNotEmpty()) append("\nPaths: ${httpConfig.paths.joinToString()}")
            else append("\nPaths: *")
            if (!parsedConfig?.methods.isNullOrEmpty()) append("\nMethods: ${parsedConfig?.methods?.joinToString()}")
            val authMethods = mutableListOf<String>()
            parsedConfig?.basicAuth?.let { authMethods.add("basic") }
            parsedConfig?.bearerAuth?.let { authMethods.add("bearer") }
            parsedConfig?.queryAuth?.let { authMethods.add("query") }
            parsedConfig?.cookieAuth?.let { authMethods.add("cookie") }
            if (authMethods.isNotEmpty()) append("\nAuth: ${authMethods.joinToString(", ")}")
            if (!parsedConfig?.allowIps.isNullOrEmpty()) append("\nAllow IPs: ${parsedConfig?.allowIps?.joinToString()}")
            if (!parsedConfig?.denyIps.isNullOrEmpty()) append("\nDeny IPs: ${parsedConfig?.denyIps?.joinToString()}")
            if (!inputError.isNullOrEmpty()) append("\nError: $inputError")
        }

        statuses.add(
            ComponentStatus(
                id = httpConfig.name,
                name = httpConfig.name,
                type = ComponentType.HTTP_INPUT,
                isEnabled = enableResult.enabled,
                isRunning = isRunning,
                notEnabledReason = enableResult.reason,
                details = details,
                error = inputError
            )
        )
    }

    // Get Link Input statuses
    appConfig?.inputs?.link?.forEach { linkConfig ->
        val enableResult = NetworkChecker.getEnableReason(context, linkConfig.whenCondition, linkConfig.deny)
        val input = InputManager.getInput(linkConfig.name)
        val isRunning = input?.isRunning() ?: false
        val displayLinkIds = if (linkConfig.linkIds.isNotEmpty()) linkConfig.linkIds else listOf(linkConfig.linkId)
        val allLinksConnected = displayLinkIds.all { id ->
            LinkManager.getLink(id)?.isConnected() ?: false
        }

        val details = buildString {
            append("Link: ${displayLinkIds.joinToString()}")
            append("\nRole: ${linkConfig.role}")
            linkConfig.topic?.let { append("\nTopic: $it") }
            linkConfig.topics?.let { append("\nTopics: ${it.joinToString()}") }
            if (!allLinksConnected) {
                val disconnectedLinks = displayLinkIds.filter { id ->
                    LinkManager.getLink(id)?.isConnected() != true
                }
                append("\n⚠ Link not connected: ${disconnectedLinks.joinToString()}")
            }
        }

        statuses.add(
            ComponentStatus(
                id = linkConfig.name,
                name = linkConfig.name,
                type = ComponentType.LINK_INPUT,
                isEnabled = enableResult.enabled,
                isRunning = isRunning,
                notEnabledReason = if (!allLinksConnected && enableResult.enabled)
                    "Link ${displayLinkIds.joinToString()} not connected"
                else
                    enableResult.reason,
                details = details
            )
        )
    }

    return statuses
}

private fun getLinkTypeString(config: LinkConfig): String {
    return when {
        config.dsn?.startsWith("mqtt") == true -> "MQTT"
        config.dsn?.startsWith("ws") == true -> "WebSocket"
        config.dsn?.startsWith("tcp") == true -> "TCP"
        config.url?.startsWith("ws") == true -> "WebSocket"
        config.url?.startsWith("wss") == true -> "WebSocket (SSL)"
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
    val (enabledComponents, disabledComponents) = getEnabledAndDisabledComponents(context)

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
    val backgroundColor = if (isEnabled) {
        when (component.type) {
            ComponentType.LINK -> Color(0xFFE3F2FD) // Light blue for links
            ComponentType.HTTP_INPUT -> Color(0xFFE8F5E9) // Light green for HTTP inputs
            ComponentType.LINK_INPUT -> Color(0xFFFFF3E0) // Light orange for link inputs
        }
    } else {
        Color(0xFFF5F5F5) // Light gray for disabled
    }

    val borderColor = if (isEnabled) {
        when (component.type) {
            ComponentType.LINK -> Color(0xFF1976D2)
            ComponentType.HTTP_INPUT -> Color(0xFF388E3C)
            ComponentType.LINK_INPUT -> Color(0xFFF57C00)
        }
    } else {
        Color(0xFFBDBDBD)
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
                        .background(
                            when {
                                component.error != null -> Color(0xFFF44336) // Red for error
                                component.isRunning -> Color(0xFF4CAF50) // Green for running
                                isEnabled -> Color(0xFFFF9800) // Orange for enabled but stopped
                                else -> Color(0xFF9E9E9E) // Gray for disabled
                            }
                        )
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
                        component.error != null -> Color(0xFFF44336)
                        component.isRunning -> Color(0xFF4CAF50)
                        else -> Color(0xFF9E9E9E)
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
                        component.error != null -> Color(0xFFF44336)
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
                    valueColor = if (component.isEnabled) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
                StatusItem(
                    label = "运行状态",
                    value = when {
                        component.error != null -> "错误"
                        component.isRunning -> "运行中"
                        else -> "已停止"
                    },
                    valueColor = when {
                        component.error != null -> Color(0xFFF44336)
                        component.isRunning -> Color(0xFF4CAF50)
                        else -> Color(0xFF9E9E9E)
                    }
                )
            }

            // Not enabled reason
            if (!component.isEnabled && component.notEnabledReason != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFF3E0)
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
                            tint = Color(0xFFF57C00)
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
                        containerColor = Color(0xFFFFEBEE)
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
                            tint = Color(0xFFF44336)
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
                                color = Color(0xFFF44336)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

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
    }
}

private fun getComponentTypeName(type: ComponentType): String {
    return when (type) {
        ComponentType.LINK -> "Link"
        ComponentType.HTTP_INPUT -> "HTTP Input"
        ComponentType.LINK_INPUT -> "Link Input"
    }
}

// Access ForwardService.currentConfig
private val forwardServiceCurrentConfig: info.loveyu.mfca.config.AppConfig?
    get() = info.loveyu.mfca.service.ForwardService.currentConfig
