@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package info.loveyu.mfca.ui.clipboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.clipboard.ClipboardRecord
import info.loveyu.mfca.ui.component.formatRelativeTime

@Composable
private fun typeBadgeColors(contentType: String): Pair<Color, Color> {
    return when (contentType) {
        "html" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        "markdown" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        "json" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "yaml" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
}

@Composable
fun ClipboardRecordWhitespaceRow(record: ClipboardRecord, summary: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) {
                    append(summary)
                }
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatRelativeTime(record.updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun ClipboardRecordShortRow(content: String, isPureUrl: Boolean, updatedAt: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = content,
            style = if (isPureUrl) {
                MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary)
            } else {
                MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatRelativeTime(updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun ClipboardRecordFullRow(
    record: ClipboardRecord,
    content: String,
    isPureUrl: Boolean,
    urls: List<String>
) {
    Text(
        text = content,
        style = if (isPureUrl) {
            MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary)
        } else {
            MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface)
        },
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (record.contentType != "text") {
                val (bgColor, fgColor) = typeBadgeColors(record.contentType)
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = bgColor
                ) {
                    Text(
                        text = record.contentType.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        color = fgColor
                    )
                }
            }
            if (record.pinned) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = "已置顶",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            if (record.notificationPinned) {
                Icon(
                    Icons.Default.NotificationImportant,
                    contentDescription = "通知栏置顶",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
            if (urls.isNotEmpty()) {
                Icon(
                    Icons.Default.Link,
                    contentDescription = if (urls.size == 1) "链接" else "${urls.size}个链接",
                    modifier = Modifier.size(14.dp),
                    tint = if (isPureUrl) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (urls.size > 1) {
                    Text(
                        text = "${urls.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Text(
            text = formatRelativeTime(record.updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
