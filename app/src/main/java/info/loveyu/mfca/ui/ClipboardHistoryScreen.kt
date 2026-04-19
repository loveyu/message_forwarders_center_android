@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package info.loveyu.mfca.ui

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import info.loveyu.mfca.ClipboardDetailActivity
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

    var deleteTargetRecord by remember { mutableStateOf<ClipboardRecord?>(null) }

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
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
            ) {
                itemsIndexed(
                    items = records,
                    key = { _, record -> record.id }
                ) { _, record ->
                    ClipboardRecordCard(
                        record = record,
                        onClick = {
                            ClipboardDetailActivity.start(context, record.id)
                        },
                        onCopy = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("Clipboard History", record.content)
                            )
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            scope.launch(Dispatchers.IO) {
                                dbHelper.insertOrUpdate(record.content, record.contentType)
                            }
                        },
                        onPinToggle = {
                            scope.launch(Dispatchers.IO) {
                                dbHelper.updatePinned(record.id, !record.pinned)
                                loadRecords()
                            }
                        },
                        onNotificationPinToggle = {
                            scope.launch(Dispatchers.IO) {
                                toggleNotificationPin(context, dbHelper, record)
                                launch(Dispatchers.Main) { loadRecords() }
                            }
                        },
                        onDelete = {
                            deleteTargetRecord = record
                        }
                    )
                }
            }
        }
    }

    deleteTargetRecord?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTargetRecord = null },
            title = { Text("删除记录") },
            text = { Text("确定要删除这条剪贴板记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        if (target.notificationPinned) {
                            target.notificationId?.let { nid ->
                                ClipboardNotificationHelper.unpinNotification(context, nid)
                            }
                        }
                        dbHelper.deleteById(target.id)
                        launch(Dispatchers.Main) {
                            deleteTargetRecord = null
                            loadRecords()
                        }
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetRecord = null }) { Text("取消") }
            }
        )
    }
}

private fun toggleNotificationPin(
    context: Context,
    dbHelper: ClipboardHistoryDbHelper,
    record: ClipboardRecord
) {
    if (record.notificationPinned) {
        record.notificationId?.let { nid ->
            ClipboardNotificationHelper.unpinNotification(context, nid)
        }
        dbHelper.updateNotificationPinned(record.id, false, null)
    } else {
        val notificationId = ClipboardNotificationHelper.getNotificationId(record.id)
        val intent = ClipboardDetailActivity.startIntent(context, record.id)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        ClipboardNotificationHelper.pinToNotification(context, record, pendingIntent)
        dbHelper.updateNotificationPinned(record.id, true, notificationId)
    }
}

@Composable
private fun ClipboardRecordCard(
    record: ClipboardRecord,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    onPinToggle: () -> Unit,
    onNotificationPinToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (record.contentType != "text") {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (record.contentType == "html") {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        ) {
                            Text(
                                text = record.contentType.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                color = if (record.contentType == "html") {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
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
                }
                Text(
                    text = formatRelativeTime(record.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = record.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onPinToggle, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = if (record.pinned) "取消置顶" else "置顶",
                        modifier = Modifier.size(16.dp),
                        tint = if (record.pinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                IconButton(onClick = onNotificationPinToggle, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.NotificationImportant,
                        contentDescription = if (record.notificationPinned) "取消通知置顶" else "通知栏置顶",
                        modifier = Modifier.size(16.dp),
                        tint = if (record.notificationPinned) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
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
