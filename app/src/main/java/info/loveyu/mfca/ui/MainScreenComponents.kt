@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import info.loveyu.mfca.ui.theme.BadgeDisabledDark
import info.loveyu.mfca.ui.theme.BadgeDisabledLight
import info.loveyu.mfca.ui.theme.BadgeEnabledDark
import info.loveyu.mfca.ui.theme.BadgeEnabledLight
import info.loveyu.mfca.ui.theme.Udp2RawChipBgDark
import info.loveyu.mfca.ui.theme.Udp2RawChipBgLight
import info.loveyu.mfca.ui.theme.Udp2RawChipBorderDark
import info.loveyu.mfca.ui.theme.Udp2RawChipBorderLight

data class ComponentSelectionKey(val id: String, val type: ComponentType)

@Composable
fun ComponentCountBadge(
    count: Int,
    label: String,
    isEnabled: Boolean
) {
    val isDark = isSystemInDarkTheme()
    val color = if (isEnabled) {
        if (isDark) BadgeEnabledDark else BadgeEnabledLight
    } else {
        if (isDark) BadgeDisabledDark else BadgeDisabledLight
    }
    Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = color)
    ) {
        Text(
            text = "$count $label",
            style = MaterialTheme.typography.labelSmall,
            color = if (isDark)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun ComponentChip(
    component: ComponentStatus,
    isEnabled: Boolean,
    onClick: () -> Unit
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
        modifier = Modifier.clickable(onClick = onClick),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = backgroundColor),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(statusDotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = component.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
