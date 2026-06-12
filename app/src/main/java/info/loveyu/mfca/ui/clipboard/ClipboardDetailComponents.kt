package info.loveyu.mfca.ui.clipboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import info.loveyu.mfca.clipboard.ClipboardRecord

private fun formatAbsoluteTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

@Composable
private fun DetailInfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun ContentTypeBadge(contentType: String) {
    val (bgColor, fgColor) = when (contentType) {
        "html" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        "json" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "yaml" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(shape = RoundedCornerShape(4.dp), color = bgColor) {
        Text(
            text = contentType.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            color = fgColor
        )
    }
}

@Composable
fun ClipboardDetailMetaSection(
    record: ClipboardRecord,
    showMetaExpanded: Boolean,
    onToggleMeta: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val timeLabel = buildString {
            append("时间: ")
            append(formatAbsoluteTime(record.updatedAt))
            if (record.createdAt != record.updatedAt) append(" (已更新)")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleMeta),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${record.content.length}字",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showMetaExpanded) {
            DetailInfoRow("创建时间", formatAbsoluteTime(record.createdAt))
            if (record.createdAt != record.updatedAt) {
                DetailInfoRow("更新时间", formatAbsoluteTime(record.updatedAt))
            }
            DetailInfoRow("哈希值", record.contentHash.take(16) + "...")
            if (record.pinned) DetailInfoRow("置顶", "是")
            if (record.notificationPinned) DetailInfoRow("通知栏置顶", "是")
        }
    }
}

@Composable
fun ClipboardDetailActions(
    record: ClipboardRecord,
    modifier: Modifier = Modifier,
    onCopy: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleNotification: () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("复制")
        }
        OutlinedButton(onClick = onTogglePin, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text(if (record.pinned) "取消置顶" else "置顶")
        }
        OutlinedButton(onClick = onToggleNotification, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.NotificationImportant, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text(if (record.notificationPinned) "取消通知" else "通知")
        }
    }
}

@Composable
fun ClipboardDetailDeleteDialog(
    record: ClipboardRecord,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除记录") },
        text = { Text("确定要删除这条剪贴板记录吗？") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
