@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package info.loveyu.mfca

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.clipboard.ClipboardHistoryDbHelper
import info.loveyu.mfca.clipboard.ClipboardNotificationHelper
import info.loveyu.mfca.clipboard.ClipboardRecord
import androidx.compose.foundation.clickable
import info.loveyu.mfca.ui.theme.MfcaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClipboardDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recordId = intent.getLongExtra("record_id", -1)
        setContent {
            MfcaTheme {
                ClipboardDetailScreen(
                    recordId = recordId,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        fun start(context: Context, recordId: Long) {
            context.startActivity(startIntent(context, recordId))
        }

        fun startIntent(context: Context, recordId: Long): Intent {
            return Intent(context, ClipboardDetailActivity::class.java).apply {
                putExtra("record_id", recordId)
            }
        }
    }
}

@Composable
private fun ClipboardDetailScreen(recordId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dbHelper = remember { ClipboardHistoryDbHelper(context) }
    var record by remember { mutableStateOf<ClipboardRecord?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMetaExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(recordId) {
        if (recordId > 0) {
            record = dbHelper.queryById(recordId)
        }
    }

    val r = record
    if (r == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("剪贴板详情") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "记录不存在或已删除",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "剪贴板详情",
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    if (ClipboardPreviewActivity.hasPreview(r.contentType)) {
                        IconButton(
                            onClick = {
                                ClipboardPreviewActivity.start(context, r.id)
                            }
                        ) {
                            Icon(
                                Icons.Default.Visibility,
                                contentDescription = "预览"
                            )
                        }
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (r.contentType != "text") {
                val (bgColor, fgColor) = when (r.contentType) {
                    "html" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                    "json" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                    "yaml" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                    else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = bgColor
                ) {
                    Text(
                        text = r.contentType.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = fgColor
                    )
                }
            }

            Text(
                text = r.content,
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.5
                )
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val timeLabel = buildString {
                    append("时间: ")
                    append(formatAbsoluteTime(r.updatedAt))
                    if (r.createdAt != r.updatedAt) append(" (已更新)")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showMetaExpanded = !showMetaExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${r.content.length}字",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showMetaExpanded) {
                    DetailInfoRow("创建时间", formatAbsoluteTime(r.createdAt))
                    if (r.createdAt != r.updatedAt) {
                        DetailInfoRow("更新时间", formatAbsoluteTime(r.updatedAt))
                    }
                    DetailInfoRow("哈希值", r.contentHash.take(16) + "...")
                    if (r.pinned) {
                        DetailInfoRow("置顶", "是")
                    }
                    if (r.notificationPinned) {
                        DetailInfoRow("通知栏置顶", "是")
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("剪贴板历史", r.content)
                        )
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        scope.launch(Dispatchers.IO) {
                            dbHelper.insertOrUpdate(r.content, r.contentType)
                            val updated = dbHelper.queryById(r.id)
                            launch(Dispatchers.Main) { record = updated }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("复制")
                }

                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            dbHelper.updatePinned(r.id, !r.pinned)
                            val updated = dbHelper.queryById(r.id)
                            launch(Dispatchers.Main) { record = updated }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(if (r.pinned) "取消置顶" else "置顶")
                }

                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            if (r.notificationPinned) {
                                r.notificationId?.let { nid ->
                                    ClipboardNotificationHelper.unpinNotification(context, nid)
                                }
                                dbHelper.updateNotificationPinned(r.id, false, null)
                            } else {
                                val notificationId =
                                    ClipboardNotificationHelper.getNotificationId(r.id)
                                val intent =
                                    ClipboardDetailActivity.startIntent(context, r.id)
                                val pendingIntent = PendingIntent.getActivity(
                                    context,
                                    notificationId,
                                    intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )
                                ClipboardNotificationHelper.pinToNotification(
                                    context,
                                    r,
                                    pendingIntent
                                )
                                dbHelper.updateNotificationPinned(r.id, true, notificationId)
                            }
                            val updated = dbHelper.queryById(r.id)
                            launch(Dispatchers.Main) { record = updated }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.NotificationImportant,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(if (r.notificationPinned) "取消通知" else "通知")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除记录") },
            text = { Text("确定要删除这条剪贴板记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        if (r.notificationPinned) {
                            r.notificationId?.let { nid ->
                                ClipboardNotificationHelper.unpinNotification(context, nid)
                            }
                        }
                        dbHelper.deleteById(r.id)
                        launch(Dispatchers.Main) {
                            showDeleteDialog = false
                            onBack()
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
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

private fun formatAbsoluteTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
