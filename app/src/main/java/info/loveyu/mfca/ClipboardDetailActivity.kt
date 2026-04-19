@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package info.loveyu.mfca

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import info.loveyu.mfca.clipboard.ClipboardHistoryDbHelper
import info.loveyu.mfca.clipboard.ClipboardNotificationHelper
import info.loveyu.mfca.clipboard.ClipboardRecord
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
    var showPreview by remember { mutableStateOf(false) }

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
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (r.contentType == "html") {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                ) {
                    Text(
                        text = r.contentType.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = if (r.contentType == "html") {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
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
                DetailInfoRow("创建时间", formatAbsoluteTime(r.createdAt))
                DetailInfoRow("更新时间", formatAbsoluteTime(r.updatedAt))
                DetailInfoRow(
                    "哈希值",
                    r.contentHash.take(16) + "..."
                )
                if (r.pinned) {
                    DetailInfoRow("置顶", "是")
                }
                if (r.notificationPinned) {
                    DetailInfoRow("通知栏置顶", "是")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("复制到剪贴板")
                }

                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            dbHelper.updatePinned(r.id, !r.pinned)
                            val updated = dbHelper.queryById(r.id)
                            launch(Dispatchers.Main) { record = updated }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.NotificationImportant,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(if (r.notificationPinned) "取消通知栏置顶" else "通知栏置顶")
                }

                if (r.contentType == "html" || r.contentType == "markdown") {
                    OutlinedButton(
                        onClick = { showPreview = !showPreview },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text(if (showPreview) "关闭预览" else "预览")
                    }
                }
            }

            if (showPreview && (r.contentType == "html" || r.contentType == "markdown")) {
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (r.contentType == "html") "HTML 预览" else "Markdown 预览",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))

                val isDark = androidx.compose.foundation.isSystemInDarkTheme()
                val previewHtml = if (r.contentType == "html") {
                    r.content
                } else {
                    markdownToHtml(r.content, isDark)
                }

                AndroidView(
                    factory = { ctx ->
                        createPreviewWebView(ctx, previewHtml)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                )
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

@SuppressLint("SetJavaScriptEnabled")
private fun createPreviewWebView(context: Context, htmlContent: String): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        settings.apply {
            javaScriptEnabled = false
            domStorageEnabled = false
        }
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
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

private fun markdownToHtml(markdown: String, isDark: Boolean): String {
    val bgColor = if (isDark) "#0d1117" else "#ffffff"
    val textColor = if (isDark) "#c9d1d9" else "#333333"
    val codeBg = if (isDark) "#161b22" else "#f6f8fa"
    val borderColor = if (isDark) "#30363d" else "#d0d7de"

    // Extract and protect code blocks first to prevent inner content from being converted
    val codeBlocks = mutableListOf<String>()
    var processed = markdown.replace(Regex("```[\\w]*\\n([\\s\\S]*?)```")) { match ->
        val placeholder = "\u0000CODE${codeBlocks.size}\u0000"
        codeBlocks.add(match.value)
        placeholder
    }

    // HTML-escape non-code content
    processed = processed
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    // Headers (longest first to avoid partial match)
    processed = processed.replace(Regex("^(#{6})\\s+(.+)$", RegexOption.MULTILINE), "<h6>$2</h6>")
    processed = processed.replace(Regex("^(#{5})\\s+(.+)$", RegexOption.MULTILINE), "<h5>$2</h5>")
    processed = processed.replace(Regex("^(#{4})\\s+(.+)$", RegexOption.MULTILINE), "<h4>$2</h4>")
    processed = processed.replace(Regex("^(#{3})\\s+(.+)$", RegexOption.MULTILINE), "<h3>$2</h3>")
    processed = processed.replace(Regex("^(#{2})\\s+(.+)$", RegexOption.MULTILINE), "<h2>$2</h2>")
    processed = processed.replace(Regex("^(#{1})\\s+(.+)$", RegexOption.MULTILINE), "<h1>$2</h1>")

    // Bold
    processed = processed.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
    // Italic
    processed = processed.replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
    // Inline code
    processed = processed.replace(Regex("`(.+?)`"), "<code>$1</code>")
    // Links
    processed = processed.replace(Regex("\\[(.+?)\\]\\((.+?)\\)"), "<a href=\"$2\">$1</a>")
    // Blockquote
    processed = processed.replace(
        Regex("^&gt;\\s+(.+)$", RegexOption.MULTILINE),
        "<blockquote>$1</blockquote>"
    )
    // Unordered list
    processed = processed.replace(Regex("^[-*+]\\s+(.+)$", RegexOption.MULTILINE), "<li>$1</li>")

    // Restore code blocks (escape HTML entities inside, wrap in pre/code tags)
    for ((index, block) in codeBlocks.withIndex()) {
        val placeholder = "\u0000CODE$index\u0000"
        // Extract content between opening and closing ```
        val contentMatch = Regex("```[\\w]*\\n([\\s\\S]*?)```").find(block)
        val innerContent = contentMatch?.groupValues?.get(1)?.let {
            it.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        } ?: ""
        processed = processed.replace(placeholder, "<pre><code>$innerContent</code></pre>")
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body {
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
                    font-size: 14px;
                    line-height: 1.6;
                    background-color: $bgColor;
                    color: $textColor;
                    margin: 8px;
                    padding: 0;
                }
                h1, h2, h3, h4, h5, h6 { margin-top: 12px; margin-bottom: 8px; font-weight: 600; }
                h1 { font-size: 1.8em; }
                h2 { font-size: 1.4em; }
                h3 { font-size: 1.2em; }
                code {
                    background-color: $codeBg;
                    padding: 0.2em 0.4em;
                    border-radius: 3px;
                    font-family: ui-monospace, SFMono-Regular, monospace;
                    font-size: 85%;
                }
                pre {
                    background-color: $codeBg;
                    border: 1px solid $borderColor;
                    border-radius: 6px;
                    padding: 12px;
                    overflow-x: auto;
                }
                pre code { background-color: transparent; padding: 0; }
                blockquote {
                    border-left: 3px solid $borderColor;
                    padding-left: 1em;
                    color: $textColor;
                    opacity: 0.7;
                    margin: 8px 0;
                }
                li { margin: 4px 0; }
                a { color: #58a6ff; }
            </style>
        </head>
        <body>$processed</body>
        </html>
    """.trimIndent()
}
