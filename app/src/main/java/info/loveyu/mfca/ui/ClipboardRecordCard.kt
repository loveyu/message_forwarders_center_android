@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package info.loveyu.mfca.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.clipboard.ClipboardPreviewActivity
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
                    ClipboardRecordWhitespaceRow(record, displayInfo.whitespaceSummary)
                } else if (!hasTags && displayInfo.isShort) {
                    ClipboardRecordShortRow(displayInfo.trimmedContent, isPureUrl, record.updatedAt)
                } else {
                    ClipboardRecordFullRow(record, displayInfo.trimmedContent, isPureUrl, urls)
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
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
                DropdownMenuItem(
                    text = { Text("详情") },
                    onClick = { showMenu = false; onOpenDetail() },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
                if (ClipboardPreviewActivity.hasPreview(record.contentType)) {
                    DropdownMenuItem(
                        text = { Text("预览") },
                        onClick = { showMenu = false; onOpenPreview() },
                        leadingIcon = {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (record.pinned) "取消置顶" else "置顶") },
                    onClick = { showMenu = false; onTogglePin() },
                    leadingIcon = {
                        Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
                if (urls.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(if (urls.size == 1) "打开链接" else "打开链接 (${urls.size})") },
                        onClick = { showMenu = false; onOpenLink() },
                        leadingIcon = {
                            Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
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
