package info.loveyu.mfca

import android.content.ClipData
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.loveyu.mfca.ui.theme.MfcaTheme
import info.loveyu.mfca.ui.webview.WebViewScreen
import java.io.IOException

data class SampleFile(
    val name: String,
    val description: String,
    val content: String
)

class HelpActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MfcaTheme {
                HelpScreenContent(
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreenContent(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedFile by remember { mutableStateOf<SampleFile?>(null) }
    var sampleFiles by remember { mutableStateOf<List<SampleFile>>(emptyList()) }

    LaunchedEffect(Unit) {
        sampleFiles = loadSampleFiles(context)
    }

    if (selectedFile != null) {
        SampleDetailScreen(
            sampleFile = selectedFile!!,
            onBack = { selectedFile = null }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("配置示例") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sampleFiles) { file ->
                    SampleFileCard(
                        sampleFile = file,
                        onClick = { selectedFile = file }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleFileCard(
    sampleFile: SampleFile,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = sampleFile.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = sampleFile.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleDetailScreen(
    sampleFile: SampleFile,
    onBack: () -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current

    val htmlTemplate = remember { context.assets.open("sample_viewer.html").bufferedReader().use { it.readText() } }
    val htmlContent = remember(sampleFile.content, isDarkTheme) {
        buildHtmlContent(htmlTemplate, sampleFile.content, sampleFile.name, isDarkTheme)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sampleFile.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(sampleFile.name, sampleFile.content))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("\uD83D\uDCCB", fontSize = 18.sp)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            WebViewScreen(
                title = sampleFile.name,
                htmlContent = htmlContent,
                isDarkTheme = isDarkTheme,
                onBack = onBack,
                rawHtml = true
            )
        }
    }
}

private fun buildHtmlContent(template: String, content: String, fileName: String, isDarkTheme: Boolean): String {
    val escapedContent = content
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    val isMarkdown = fileName.endsWith(".md", ignoreCase = true)
    val languageClass = if (isMarkdown) "" else " class=\"language-yaml\""
    val viewClass = if (isMarkdown) "md-view" else "yaml-view"
    val themeClass = if (isDarkTheme) "dark" else "light"
    val highlightStyle = if (isDarkTheme) {
        "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark-dimmed.min.css"
    } else {
        "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github.min.css"
    }

    val markdownScript = if (isMarkdown) {
        """
            var md = window.markdownit({ linkify: false, breaks: true });
            md.renderer.rules.link_open = function() { return ''; };
            md.renderer.rules.link_close = function() { return ''; };
            document.getElementById('content-area').innerHTML = md.render(content);
            document.querySelectorAll('pre code').forEach(function(block) {
                hljs.highlightElement(block);
            });
            addCopyButtons();
        """
    } else {
        """
            hljs.highlightElement(document.getElementById('content'));
        """
    }

    return template
        .replace("{{THEME_CLASS}}", themeClass)
        .replace("{{HIGHLIGHT_STYLE}}", highlightStyle)
        .replace("{{VIEW_CLASS}}", viewClass)
        .replace("{{LANGUAGE_CLASS}}", languageClass)
        .replace("{{CONTENT}}", escapedContent)
        .replace("{{IS_MARKDOWN}}", isMarkdown.toString())
        .replace("{{MARKDOWN_SCRIPT}}", markdownScript)
}

private fun loadSampleFiles(context: Context): List<SampleFile> {
    val sampleList = listOf(
        SampleFileInfo(
            fileName = "README.md",
            description = "配置说明 - 完整配置语法和协议说明"
        ),
        SampleFileInfo(
            fileName = "01_basic_mqtt.yaml",
            description = "MQTT 基础连接 - DSN 格式、TLS 支持"
        ),
        SampleFileInfo(
            fileName = "02_websocket_link.yaml",
            description = "WebSocket 连接 - WS/WSS 配置"
        ),
        SampleFileInfo(
            fileName = "03_tcp_link.yaml",
            description = "TCP 连接 - Socket 配置"
        ),
        SampleFileInfo(
            fileName = "04_network_conditions.yaml",
            description = "网络条件控制 - when/deny 条件"
        ),
        SampleFileInfo(
            fileName = "05_http_input.yaml",
            description = "HTTP 输入 - NanoHTTPD、认证方式"
        ),
        SampleFileInfo(
            fileName = "06_link_input_output.yaml",
            description = "Link 输入输出 - 订阅/发布示例"
        ),
        SampleFileInfo(
            fileName = "07_memory_queue.yaml",
            description = "内存队列 - 高性能临时缓冲"
        ),
        SampleFileInfo(
            fileName = "08_sqlite_queue.yaml",
            description = "SQLite 持久化队列 - 重试、退避、清理"
        ),
        SampleFileInfo(
            fileName = "09_outputs.yaml",
            description = "输出模块 - HTTP/Link/Internal 输出"
        ),
        SampleFileInfo(
            fileName = "10_rules.yaml",
            description = "规则引擎 - 提取、过滤、检测"
        ),
        SampleFileInfo(
            fileName = "11_clipboard_forward.yaml",
            description = "剪贴板转发 - MQTT 到本地剪贴板"
        ),
        SampleFileInfo(
            fileName = "12_http_shared_input.yaml",
            description = "HTTP 共享输入 - 多转发器共享输入配置"
        ),
        SampleFileInfo(
            fileName = "13_quick_settings.yaml",
            description = "快捷设置 - 通知栏按钮开关"
        ),
        SampleFileInfo(
            fileName = "14_scheduler.yaml",
            description = "调度器配置 - 定时检查间隔、锁超时"
        ),
        SampleFileInfo(
            fileName = "99_full_demo.yaml",
            description = "完整演示 - 智能家居场景"
        )
    )

    return sampleList.mapNotNull { info ->
        try {
            val content = context.assets.open("samples/${info.fileName}")
                .bufferedReader()
                .use { it.readText() }
            SampleFile(
                name = info.fileName,
                description = info.description,
                content = content
            )
        } catch (e: IOException) {
            null
        }
    }
}

private data class SampleFileInfo(
    val fileName: String,
    val description: String
)
