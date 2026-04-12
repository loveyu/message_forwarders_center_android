package info.loveyu.mfca.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.URLUtil
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import info.loveyu.mfca.ui.webview.WebViewScreen
import java.io.IOException

data class SampleFile(
    val name: String,
    val description: String,
    val content: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
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
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()

    val htmlContent = remember(sampleFile.content) {
        buildHtmlContent(sampleFile.content, sampleFile.name)
    }

    WebViewScreen(
        title = sampleFile.name,
        htmlContent = htmlContent,
        isDarkTheme = isDarkTheme,
        onBack = onBack
    )
}

private fun buildHtmlContent(content: String, fileName: String): String {
    val escapedContent = content
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("$", "\\$")

    val isMarkdown = fileName.endsWith(".md", ignoreCase = true)

    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github.min.css">
    <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/marked@9.1.6/lib/marked.umd.js"></script>
    <style>
        :root {
            --bg-color: #ffffff;
            --text-color: #333333;
            --code-bg: #f6f8fa;
            --border-color: #d0d7de;
            --link-color: #0969da;
            --blockquote-color: #656d76;
            --scrollbar-thumb: #c1c1c1;
        }

        @media (prefers-color-scheme: dark) {
            :root {
                --bg-color: #0d1117;
                --text-color: #c9d1d9;
                --code-bg: #161b22;
                --border-color: #30363d;
                --link-color: #58a6ff;
                --blockquote-color: #8b949e;
                --scrollbar-thumb: #484f58;
            }
        }

        [data-theme="dark"] {
            --bg-color: #0d1117 !important;
            --text-color: #c9d1d9 !important;
            --code-bg: #161b22 !important;
            --border-color: #30363d !important;
            --link-color: #58a6ff !important;
            --blockquote-color: #8b949e !important;
            --scrollbar-thumb: #484f58 !important;
        }

        [data-theme="light"] {
            --bg-color: #ffffff !important;
            --text-color: #333333 !important;
            --code-bg: #f6f8fa !important;
            --border-color: #d0d7de !important;
            --link-color: #0969da !important;
            --blockquote-color: #656d76 !important;
            --scrollbar-thumb: #c1c1c1 !important;
        }

        * { box-sizing: border-box; }

        body {
            margin: 0;
            padding: 16px;
            background-color: var(--bg-color);
            color: var(--text-color);
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
            font-size: 14px;
            line-height: 1.6;
            word-wrap: break-word;
        }

        pre {
            background-color: var(--code-bg) !important;
            border: 1px solid var(--border-color);
            border-radius: 6px;
            padding: 12px !important;
            overflow-x: auto;
            margin: 8px 0;
        }

        code {
            font-family: ui-monospace, SFMono-Regular, SF Mono, Menlo, Consolas, monospace;
            font-size: 85%;
        }

        pre code {
            background-color: transparent !important;
            padding: 0 !important;
        }

        :not(pre) > code {
            background-color: var(--code-bg);
            padding: 0.2em 0.4em;
            border-radius: 4px;
        }

        a { color: var(--link-color); text-decoration: none; }
        a:hover { text-decoration: underline; }

        blockquote {
            margin: 0;
            padding: 0 1em;
            color: var(--blockquote-color);
            border-left: 0.25em solid var(--border-color);
        }

        table {
            border-collapse: collapse;
            width: 100%;
            margin: 8px 0;
        }

        table th, table td {
            border: 1px solid var(--border-color);
            padding: 6px 13px;
        }

        table tr:nth-child(2n) {
            background-color: var(--code-bg);
        }

        hr {
            border: none;
            border-top: 1px solid var(--border-color);
            margin: 24px 0;
        }

        h1, h2, h3, h4, h5, h6 {
            margin-top: 24px;
            margin-bottom: 16px;
            font-weight: 600;
            line-height: 1.25;
        }

        h1 { font-size: 2em; border-bottom: 1px solid var(--border-color); padding-bottom: 0.3em; }
        h2 { font-size: 1.5em; border-bottom: 1px solid var(--border-color); padding-bottom: 0.3em; }
        h3 { font-size: 1.25em; }
        h4 { font-size: 1em; }
        h5 { font-size: 0.875em; }
        h6 { font-size: 0.85em; color: var(--blockquote-color); }

        ul, ol { padding-left: 2em; margin: 8px 0; }
        li + li { margin-top: 4px; }

        ::-webkit-scrollbar { width: 8px; height: 8px; }
        ::-webkit-scrollbar-track { background: var(--bg-color); }
        ::-webkit-scrollbar-thumb { background: var(--scrollbar-thumb); border-radius: 4px; }
        ::-webkit-scrollbar-thumb:hover { background: var(--blockquote-color); }
    </style>
</head>
<body>
    <pre><code id="content">${escapedContent}</code></pre>
    <script>
        (function() {
            var content = document.getElementById('content').textContent;

            ${if (isMarkdown) """
            // Render as Markdown
            document.getElementById('content').innerHTML = marked.parse(content);
            document.querySelectorAll('pre code').forEach(function(block) {
                hljs.highlightElement(block);
            });
            """ else """
            // Render as YAML/syntax highlighted code
            hljs.highlightElement(document.getElementById('content'));
            """}

            // Auto-detect and apply theme
            if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
                document.documentElement.setAttribute('data-theme', 'dark');
            } else {
                document.documentElement.setAttribute('data-theme', 'light');
            }
        })();
    </script>
</body>
</html>
    """.trimIndent()
}

/**
 * 复制文本到剪贴板
 */
private fun copyToClipboard(context: Context, content: String, fileName: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(fileName, content)
    clipboard.setPrimaryClip(clip)

    // 显示 Toast 提示
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, "已复制 $fileName 到剪贴板", Toast.LENGTH_SHORT).show()
    }
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
            fileName = "11_full_demo.yaml",
            description = "完整演示 - 智能家居场景"
        ),
        SampleFileInfo(
            fileName = "12_clipboard_forward.yaml",
            description = "剪贴板转发 - MQTT 到本地剪贴板"
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
