@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package info.loveyu.mfca.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import info.loveyu.mfca.ClipboardDetailActivity
import info.loveyu.mfca.ClipboardPreviewActivity
import info.loveyu.mfca.clipboard.ClipboardHistoryDbHelper
import info.loveyu.mfca.clipboard.ClipboardNotificationHelper
import info.loveyu.mfca.clipboard.ClipboardRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun ClipboardHistoryTopBar() {
    TopAppBar(
        title = { Text("剪贴板历史") }
    )
}

@OptIn(FlowPreview::class)
@Composable
fun ClipboardHistoryContent(
    onBack: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var records by remember { mutableStateOf<List<ClipboardRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var totalCount by remember { mutableStateOf(0) }
    var searchKeyword by remember { mutableStateOf("") }
    var linkPickerUrls by remember { mutableStateOf<List<String>>(emptyList()) }

    val dbHelper = remember { ClipboardHistoryDbHelper(context) }

    fun loadRecords() {
        scope.launch(Dispatchers.IO) {
            val result = dbHelper.query(
                keyword = searchKeyword.ifBlank { null },
                limit = 200
            )
            val count = dbHelper.count(keyword = searchKeyword.ifBlank { null })
            launch(Dispatchers.Main) {
                records = result
                totalCount = count
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadRecords() }

    val dbVersion by ClipboardHistoryDbHelper.changeVersion.collectAsState()
    LaunchedEffect(dbVersion) {
        if (dbVersion > 0) loadRecords()
    }

    LifecycleResumeEffect(Unit) {
        loadRecords()
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { searchKeyword }
            .debounce(300)
            .distinctUntilChanged()
            .collect { loadRecords() }
    }

    fun copyRecord(record: ClipboardRecord) {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Clipboard History", record.content)
        )
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.IO) {
            dbHelper.insertOrUpdate(record.content, record.contentType)
        }
    }

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    fun togglePin(record: ClipboardRecord) {
        scope.launch(Dispatchers.IO) {
            dbHelper.updatePinned(record.id, !record.pinned)
            loadRecords()
        }
    }

    fun deleteRecord(record: ClipboardRecord) {
        scope.launch(Dispatchers.IO) {
            if (record.notificationPinned) {
                record.notificationId?.let { nid ->
                    ClipboardNotificationHelper.unpinNotification(context, nid)
                }
            }
            dbHelper.deleteById(record.id)
            launch(Dispatchers.Main) { loadRecords() }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        OutlinedTextField(
            value = searchKeyword,
            onValueChange = { searchKeyword = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(48.dp),
            placeholder = {
                Text(
                    "搜索内容...",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            textStyle = MaterialTheme.typography.bodyLarge
        )

        if (searchKeyword.isNotBlank() && totalCount > 0) {
            Text(
                text = "共 $totalCount 条记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (records.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "暂无剪贴板记录",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                itemsIndexed(
                    items = records,
                    key = { _, record -> record.id }
                ) { _, record ->
                    val urls = remember(record.content) { extractUrls(record.content) }
                    val isPureUrl = remember(record.content) { isSingleUrl(record.content) }

                    ClipboardRecordCard(
                        record = record,
                        urls = urls,
                        isPureUrl = isPureUrl,
                        onCopy = { copyRecord(record) },
                        onOpenDetail = {
                            ClipboardDetailActivity.start(context, record.id)
                        },
                        onOpenPreview = {
                            ClipboardPreviewActivity.start(context, record.id)
                        },
                        onTogglePin = {
                            scope.launch(Dispatchers.IO) {
                                dbHelper.updatePinned(record.id, !record.pinned)
                                loadRecords()
                            }
                        },
                        onOpenLink = {
                            when {
                                urls.isEmpty() -> {}
                                urls.size == 1 -> openUrl(urls.first())
                                else -> linkPickerUrls = urls
                            }
                        },
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                if (record.notificationPinned) {
                                    record.notificationId?.let { nid ->
                                        ClipboardNotificationHelper.unpinNotification(context, nid)
                                    }
                                }
                                dbHelper.deleteById(record.id)
                                launch(Dispatchers.Main) { loadRecords() }
                            }
                        },
                        onClick = {
                            if (isPureUrl && urls.size == 1) {
                                openUrl(urls.first())
                            } else {
                                ClipboardDetailActivity.start(context, record.id)
                            }
                        }
                    )
                }
            }
        }
    }

    // Link picker bottom sheet
    if (linkPickerUrls.isNotEmpty()) {
        LinkPickerSheet(
            urls = linkPickerUrls,
            onUrlClick = { url ->
                linkPickerUrls = emptyList()
                openUrl(url)
            },
            onDismiss = { linkPickerUrls = emptyList() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkPickerSheet(
    urls: List<String>,
    onUrlClick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

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
                text = "选择链接",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            urls.forEach { url ->
                TextButton(
                    onClick = { onUrlClick(url) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipboardRecordCard(
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
    val hasTags = record.contentType != "text" || record.pinned || record.notificationPinned || urls.isNotEmpty()
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
                    // Whitespace: single line with summary + time
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) {
                                    append(displayInfo.whitespaceSummary)
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
                } else if (!hasTags && displayInfo.isShort) {
                    // Short content without tags: single line content + time
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayInfo.trimmedContent,
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
                            text = formatRelativeTime(record.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                } else {
                    // Multi-line or has tags: content + bottom row
                    Text(
                        text = displayInfo.trimmedContent,
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
                        // Left: badges
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
                        // Right: time
                        Text(
                            text = formatRelativeTime(record.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Context menu anchored to card
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("复制") },
                    onClick = {
                        showMenu = false
                        onCopy()
                    },
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
                    onClick = {
                        showMenu = false
                        onOpenDetail()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
                if (ClipboardPreviewActivity.hasPreview(record.contentType)) {
                    DropdownMenuItem(
                        text = { Text("预览") },
                        onClick = {
                            showMenu = false
                            onOpenPreview()
                        },
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
                    onClick = {
                        showMenu = false
                        onTogglePin()
                    },
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
                        onClick = {
                            showMenu = false
                            onOpenLink()
                        },
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
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
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

// --- URL detection helpers ---

private val URL_REGEX = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")

private fun extractUrls(content: String): List<String> {
    return URL_REGEX.findAll(content).map { it.value.trimEnd(',', '.', ';', '!', '?', ':', ')') }
        .distinct().toList()
}

private fun isSingleUrl(content: String): Boolean {
    val trimmed = content.trim()
    return URL_REGEX.matches(trimmed)
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}分钟前"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}小时前"
        diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)}天前"
        else -> formatAbsoluteTime(timestamp)
    }
}

private fun formatAbsoluteTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

@Composable
private fun typeBadgeColors(contentType: String): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val c = when (contentType) {
        "html" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        "markdown" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        "json" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "yaml" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    return c
}
