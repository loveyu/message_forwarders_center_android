package info.loveyu.mfca.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.ui.theme.DisabledChipBgDark
import info.loveyu.mfca.ui.theme.DisabledChipBgLight
import info.loveyu.mfca.ui.theme.DisabledChipBorderDark
import info.loveyu.mfca.ui.theme.DisabledChipBorderLight
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
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (isEnabled) {
        when (component.type) {
            ComponentType.LINK -> if (isDark) LinkChipBgDark else LinkChipBgLight
            ComponentType.HTTP_INPUT -> if (isDark) HttpInputChipBgDark else HttpInputChipBgLight
            ComponentType.LINK_INPUT -> if (isDark) LinkInputChipBgDark else LinkInputChipBgLight
            ComponentType.UDP2RAW -> if (isDark) Udp2RawChipBgDark else Udp2RawChipBgLight
            ComponentType.RULE -> if (isDark) LinkInputChipBgDark else LinkInputChipBgLight
            ComponentType.OUTPUT -> if (isDark) OutputChipBgDark else OutputChipBgLight
            ComponentType.QUEUE -> if (isDark) QueueChipBgDark else QueueChipBgLight
        }
    } else {
        if (isDark) DisabledChipBgDark else DisabledChipBgLight
    }
    val borderColor = if (isEnabled) {
        when (component.type) {
            ComponentType.LINK -> if (isDark) LinkChipBorderDark else LinkChipBorderLight
            ComponentType.HTTP_INPUT -> if (isDark) HttpInputChipBorderDark else HttpInputChipBorderLight
            ComponentType.LINK_INPUT -> if (isDark) LinkInputChipBorderDark else LinkInputChipBorderLight
            ComponentType.UDP2RAW -> if (isDark) Udp2RawChipBorderDark else Udp2RawChipBorderLight
            ComponentType.RULE -> if (isDark) LinkInputChipBorderDark else LinkInputChipBorderLight
            ComponentType.OUTPUT -> if (isDark) OutputChipBorderDark else OutputChipBorderLight
            ComponentType.QUEUE -> if (isDark) QueueChipBorderDark else QueueChipBorderLight
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
            .clickable { },
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusDotColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
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
