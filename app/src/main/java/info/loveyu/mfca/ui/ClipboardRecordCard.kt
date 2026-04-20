@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package info.loveyu.mfca.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.ClipboardDetailActivity
import info.loveyu.mfca.ClipboardPreviewActivity
import info.loveyu.mfca.clipboard.ClipboardRecord

@Composable
fun ClipboardRecordCard(
    record: ClipboardRecord,
    urls: List<String>,
    isPureUrl: Boolean,
    onCopy: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenPreview: () -> Unit,
    onTogglePin: () -> Unit,
    onOpenLink: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    val displayInfo = remember(record.content) { getDisplayInfo(record.content) }
    val hasTags =
        record.contentType != "text" || record.pinned || record.notificationPinned || urls.isNotEmpty()
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (displayInfo.isAllWhitespace) {
                    WhitespaceRow(record, displayInfo.whitespaceSummary)
                } else if (!hasTags && displayInfo.isShort) {
                    ShortContentRow(displayInfo.trimmedContent, isPureUrl, record.updatedAt)
                } else {
                    FullContentRow(record, displayInfo.trimmedContent, isPureUrl, urls)
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("复制") },
                    onClick = { showMenu = false; onCopy() },
                    leadingIcon = {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("详情") },
                    onClick = { showMenu = false; onOpenDetail() },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
                if (ClipboardPreviewActivity.hasPreview(record.contentType)) {
                    DropdownMenuItem(
                        text = { Text("预览") },
                        onClick = { showMenu = false; onOpenPreview() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (record.pinned) "取消置顶" else "置顶") },
                    onClick = { showMenu = false; onTogglePin() },
                    leadingIcon = {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
                if (urls.isNotEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(if (urls.size == 1) "打开链接" else "打开链接 (${urls.size})")
                        },
                        onClick = { showMenu = false; onOpenLink() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    onClick = { showMenu = false; onDelete() },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                )
            }
        }
    }
}

// --- Card layout variants ---

@Composable
private fun WhitespaceRow(record: ClipboardRecord, summary: String) {
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
private fun ShortContentRow(content: String, isPureUrl: Boolean, updatedAt: Long) {
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
private fun FullContentRow(
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

// --- Content display helpers ---

private data class DisplayInfo(
    val trimmedContent: String,
    val isAllWhitespace: Boolean,
    val isShort: Boolean,
    val whitespaceSummary: String
)

private fun getDisplayInfo(content: String): DisplayInfo {
    val isAllWhitespace = content.isNotEmpty() && content.trim().isEmpty()
    if (isAllWhitespace) {
        val summary = buildWhitespaceSummary(content)
        return DisplayInfo(
            trimmedContent = "",
            isAllWhitespace = true,
            isShort = true,
            whitespaceSummary = summary
        )
    }
    val trimmed = content.lines().joinToString("\n") { it.trim() }.trim()
    val short = !trimmed.contains('\n') && trimmed.length <= 60
    return DisplayInfo(
        trimmedContent = trimmed,
        isAllWhitespace = false,
        isShort = short,
        whitespaceSummary = ""
    )
}

private fun buildWhitespaceSummary(content: String): String {
    val total = content.length
    val spaces = content.count { it == ' ' }
    val tabs = content.count { it == '\t' }
    val newlines = content.count { it == '\n' }
    val others = total - spaces - tabs - newlines

    val parts = mutableListOf<String>()
    if (newlines > 0) parts.add("${newlines}换行")
    if (spaces > 0) parts.add("${spaces}空格")
    if (tabs > 0) parts.add("${tabs}制表符")
    if (others > 0) parts.add("${others}其他")
    val detail = if (parts.isNotEmpty()) parts.joinToString(" ") else ""

    return "空白字符($total)" + if (detail.isNotEmpty()) " · $detail" else ""
}

fun isSingleUrl(content: String): Boolean {
    val trimmed = content.trim()
    return URL_REGEX.matches(trimmed)
}

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
