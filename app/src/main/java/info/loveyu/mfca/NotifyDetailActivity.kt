@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package info.loveyu.mfca

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.notification.NotifyHistoryDbHelper
import info.loveyu.mfca.notification.NotifyRecord
import info.loveyu.mfca.ui.theme.MfcaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotifyDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recordId = intent.getLongExtra("record_id", -1)
        setContent {
            MfcaTheme {
                NotifyDetailScreen(
                    recordId = recordId,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        fun start(context: Context, recordId: Long) {
            context.startActivity(Intent(context, NotifyDetailActivity::class.java).apply {
                putExtra("record_id", recordId)
            })
        }
    }
}

@Composable
private fun NotifyDetailScreen(recordId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dbHelper = remember { NotifyHistoryDbHelper(context) }
    var record by remember { mutableStateOf<NotifyRecord?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

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
                    title = { Text("通知详情") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("通知不存在或已删除", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(r.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
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
            DetailInfoRow("时间", formatAbsoluteTime(r.createdAt))
            r.sourceRule?.let { DetailInfoRow("来源规则", it) }
            DetailInfoRow("输出名称", r.outputName)
            DetailInfoRow("渠道", r.channel)
            r.tag?.let { DetailInfoRow("标签", it) }
            r.group?.let { DetailInfoRow("分组", it) }
            r.iconUrl?.let { DetailInfoRow("图标", it) }
            if (r.popup) DetailInfoRow("弹出通知", "是")
            if (r.persistent) DetailInfoRow("常驻通知", "是")

            Spacer(modifier = Modifier.height(4.dp))

            Text(text = "内容", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text(text = r.content, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
            }

            if (!r.rawData.isNullOrBlank()) {
                var showRawData by remember { mutableStateOf(false) }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { showRawData = !showRawData }) {
                    Text(if (showRawData) "收起原始数据" else "展开原始数据")
                }
                if (showRawData) {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = formatRawData(r.rawData),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Copy and Delete actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("通知内容", r.content))
                        Toast.makeText(context, "已复制内容", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("复制内容") }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除通知") },
            text = { Text("确定要删除这条通知记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
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
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = "$label: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatAbsoluteTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

private fun formatRawData(rawData: String): String {
    return try {
        val trimmed = rawData.trimStart()
        if (trimmed.startsWith("{")) org.json.JSONObject(rawData).toString(2)
        else if (trimmed.startsWith("[")) org.json.JSONArray(rawData).toString(2)
        else rawData
    } catch (e: Exception) { rawData }
}
