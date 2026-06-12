package info.loveyu.mfca.ui.allcomponents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.ui.theme.DisabledChipBgDark
import info.loveyu.mfca.ui.theme.DisabledChipBgLight
import info.loveyu.mfca.ui.theme.DisabledChipBorderDark
import info.loveyu.mfca.ui.theme.DisabledChipBorderLight
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
import info.loveyu.mfca.ui.theme.OutputChipBgDark
import info.loveyu.mfca.ui.theme.OutputChipBgLight
import info.loveyu.mfca.ui.theme.OutputChipBorderDark
import info.loveyu.mfca.ui.theme.OutputChipBorderLight
import info.loveyu.mfca.ui.theme.QueueChipBgDark
import info.loveyu.mfca.ui.theme.QueueChipBgLight
import info.loveyu.mfca.ui.theme.QueueChipBorderDark
import info.loveyu.mfca.ui.theme.QueueChipBorderLight
import info.loveyu.mfca.ui.theme.StatusDisabledDark
import info.loveyu.mfca.ui.theme.StatusDisabledLight
import info.loveyu.mfca.ui.theme.StatusErrorDark
import info.loveyu.mfca.ui.theme.StatusErrorLight
import info.loveyu.mfca.ui.theme.StatusRunningDark
import info.loveyu.mfca.ui.theme.StatusRunningLight
import info.loveyu.mfca.ui.theme.StatusWarningDark
import info.loveyu.mfca.ui.theme.StatusWarningLight
import info.loveyu.mfca.ui.theme.Udp2RawChipBgDark
import info.loveyu.mfca.ui.theme.Udp2RawChipBgLight
import info.loveyu.mfca.ui.theme.Udp2RawChipBorderDark
import info.loveyu.mfca.ui.theme.Udp2RawChipBorderLight
import info.loveyu.mfca.ui.component.ComponentStatus
import info.loveyu.mfca.ui.component.ComponentType
import info.loveyu.mfca.ui.component.getComponentIcon
import info.loveyu.mfca.ui.component.getComponentTypeName

@Composable
fun ComponentGroupHeader(type: ComponentType, count: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = getComponentTypeName(type),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        HorizontalDivider()
    }
}

@Composable
fun ComponentListItem(
    component: ComponentStatus,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (component.isEnabled) {
        when (component.type) {
            ComponentType.LINK -> if (isDark) LinkChipBgDark else LinkChipBgLight
            ComponentType.HTTP_INPUT -> if (isDark) HttpInputChipBgDark else HttpInputChipBgLight
            ComponentType.LINK_INPUT, ComponentType.RULE -> if (isDark) LinkInputChipBgDark else LinkInputChipBgLight
            ComponentType.UDP2RAW -> if (isDark) Udp2RawChipBgDark else Udp2RawChipBgLight
            ComponentType.OUTPUT -> if (isDark) OutputChipBgDark else OutputChipBgLight
            ComponentType.QUEUE -> if (isDark) QueueChipBgDark else QueueChipBgLight
        }
    } else {
        if (isDark) DisabledChipBgDark else DisabledChipBgLight
    }
    val borderColor = if (component.isEnabled) {
        when (component.type) {
            ComponentType.LINK -> if (isDark) LinkChipBorderDark else LinkChipBorderLight
            ComponentType.HTTP_INPUT -> if (isDark) HttpInputChipBorderDark else HttpInputChipBorderLight
            ComponentType.LINK_INPUT, ComponentType.RULE -> if (isDark) LinkInputChipBorderDark else LinkInputChipBorderLight
            ComponentType.UDP2RAW -> if (isDark) Udp2RawChipBorderDark else Udp2RawChipBorderLight
            ComponentType.OUTPUT -> if (isDark) OutputChipBorderDark else OutputChipBorderLight
            ComponentType.QUEUE -> if (isDark) QueueChipBorderDark else QueueChipBorderLight
        }
    } else {
        if (isDark) DisabledChipBorderDark else DisabledChipBorderLight
    }
    val statusColor = when {
        component.error != null -> if (isDark) StatusErrorDark else StatusErrorLight
        component.isRunning -> if (isDark) StatusRunningDark else StatusRunningLight
        component.isEnabled -> if (isDark) StatusWarningDark else StatusWarningLight
        else -> if (isDark) StatusDisabledDark else StatusDisabledLight
    }
    val accentColor = when {
        component.error != null -> if (isDark) ErrorCardIconDark else ErrorCardIconLight
        else -> borderColor
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = getComponentIcon(component.type),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = component.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = getComponentTypeName(component.type),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = when {
                        component.error != null -> Icons.Default.Warning
                        component.isRunning -> Icons.Default.CheckCircle
                        else -> Icons.Default.Close
                    },
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            Text(
                text = when {
                    component.error != null -> "错误"
                    component.isRunning -> "运行中"
                    component.isEnabled -> "待命"
                    else -> "未启用"
                },
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )

            // Per-upstream status indicators for multi-link outputs
            if (component.upstreamLinks.size > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "上游:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    component.upstreamLinks.forEach { upstream ->
                        val dotColor = when {
                            upstream.isConnected -> if (isDark) StatusRunningDark else StatusRunningLight
                            upstream.isNetworkEnabled -> if (isDark) StatusWarningDark else StatusWarningLight
                            else -> if (isDark) StatusDisabledDark else StatusDisabledLight
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                    val connectedCount = component.upstreamLinks.count { it.isConnected }
                    Text(
                        text = "$connectedCount/${component.upstreamLinks.size} 已连接",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (component.notEnabledReason != null) {
                Text(
                    text = component.notEnabledReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = component.details.lineSequence().take(3).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
