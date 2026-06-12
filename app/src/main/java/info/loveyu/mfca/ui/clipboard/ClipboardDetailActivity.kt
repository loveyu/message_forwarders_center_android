@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package info.loveyu.mfca.ui.clipboard

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import info.loveyu.mfca.ui.theme.MfcaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                    Text("剪贴板详情", maxLines = 1)
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
                        IconButton(onClick = { ClipboardPreviewActivity.start(context, r.id) }) {
                            Icon(Icons.Default.Visibility, contentDescription = "预览")
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
                ContentTypeBadge(contentType = r.contentType)
            }

            Text(
                text = r.content,
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.5
                )
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            ClipboardDetailMetaSection(
                record = r,
                showMetaExpanded = showMetaExpanded,
                onToggleMeta = { showMetaExpanded = !showMetaExpanded },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            ClipboardDetailActions(
                record = r,
                onCopy = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("剪贴板历史", r.content))
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    scope.launch(Dispatchers.IO) {
                        dbHelper.insertOrUpdate(r.content, r.contentType)
                        val updated = dbHelper.queryById(r.id)
                        launch(Dispatchers.Main) { record = updated }
                    }
                },
                onTogglePin = {
                    scope.launch(Dispatchers.IO) {
                        dbHelper.updatePinned(r.id, !r.pinned)
                        val updated = dbHelper.queryById(r.id)
                        launch(Dispatchers.Main) { record = updated }
                    }
                },
                onToggleNotification = {
                    scope.launch(Dispatchers.IO) {
                        if (r.notificationPinned) {
                            r.notificationId?.let { nid ->
                                ClipboardNotificationHelper.unpinNotification(context, nid)
                            }
                            dbHelper.updateNotificationPinned(r.id, false, null)
                        } else {
                            val notificationId = ClipboardNotificationHelper.getNotificationId(r.id)
                            val intent = ClipboardDetailActivity.startIntent(context, r.id)
                            val pendingIntent = PendingIntent.getActivity(
                                context,
                                notificationId,
                                intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            ClipboardNotificationHelper.pinToNotification(context, r, pendingIntent)
                            dbHelper.updateNotificationPinned(r.id, true, notificationId)
                        }
                        val updated = dbHelper.queryById(r.id)
                        launch(Dispatchers.Main) { record = updated }
                    }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showDeleteDialog) {
        ClipboardDetailDeleteDialog(
            record = r,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
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
            },
        )
    }
}
