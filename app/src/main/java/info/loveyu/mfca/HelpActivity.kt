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

    val htmlContent = remember(sampleFile.content) {
        buildHtmlContent(sampleFile.content, sampleFile.name)
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

private fun buildHtmlContent(content: String, fileName: String): String {
    val escapedContent = content
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    val isMarkdown = fileName.endsWith(".md", ignoreCase = true)
    val languageClass = if (isMarkdown) "" else " class=\"language-yaml\""
    val viewClass = if (isMarkdown) "md-view" else "yaml-view"

    val markdownScript = if (isMarkdown) """
            var md = window.markdownit({ linkify: false, breaks: true });
            md.renderer.rules.link_open = function() { return ''; };
            md.renderer.rules.link_close = function() { return ''; };
            document.getElementById('content-area').innerHTML = md.render(content);
            document.querySelectorAll('pre code').forEach(function(block) {
                hljs.highlightElement(block);
            });
            addCopyButtons();
    """ else """
            hljs.highlightElement(document.getElementById('content'));
    """

    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github.min.css">
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark-dimmed.min.css" media="(prefers-color-scheme: dark)">
    <style>
        :root {
            --bg-color: #ffffff;
            --text-color: #333333;
            --code-bg: #f6f8fa;
            --border-color: #d0d7de;
            --link-color: #0969da;
            --blockquote-color: #656d76;
            --copy-btn-bg: #e8e8e8;
            --copy-btn-hover: #d4d4d4;
            --copy-success-color: #28a745;
        }

        [data-theme="dark"] {
            --bg-color: #0d1117;
            --text-color: #c9d1d9;
            --code-bg: #161b22;
            --border-color: #30363d;
            --link-color: #58a6ff;
            --blockquote-color: #8b949e;
            --copy-btn-bg: #2d333b;
            --copy-btn-hover: #3d444d;
            --copy-success-color: #3fb950;
        }

        * {
            box-sizing: border-box;
            -webkit-tap-highlight-color: transparent;
        }

        html, body {
            margin: 0;
            padding: 0;
            height: 100%;
            overflow: hidden;
            -webkit-user-select: text;
            user-select: text;
        }

        body {
            background-color: var(--bg-color);
            color: var(--text-color);
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
            font-size: 14px;
            line-height: 1.6;
        }

        .content {
            height: 100%;
            overflow-y: auto;
            padding: 8px;
            -webkit-overflow-scrolling: touch;
        }

        /* YAML 文件：顶级代码块无装饰 */
        .yaml-view pre {
            background-color: transparent !important;
            border: none;
            border-radius: 0;
            padding: 0 !important;
            overflow-x: auto;
            margin: 0;
        }

        /* MD 文件：内部代码块有边框背景 */
        .md-view .code-wrapper {
            position: relative;
            margin: 4px 0;
        }

        .md-view pre {
            background-color: var(--code-bg) !important;
            border: 1px solid var(--border-color);
            border-radius: 4px;
            padding: 8px !important;
            padding-right: 48px !important;
            overflow-x: auto;
            margin: 0;
        }

        code {
            font-family: ui-monospace, SFMono-Regular, SF Mono, Menlo, Consolas, monospace;
            font-size: 12px;
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

        .copy-btn {
            position: absolute;
            top: 8px;
            right: 8px;
            width: 32px;
            height: 32px;
            display: flex;
            align-items: center;
            justify-content: center;
            background-color: var(--copy-btn-bg);
            border: 1px solid var(--border-color);
            border-radius: 6px;
            cursor: pointer;
            color: var(--text-color);
            font-size: 14px;
            transition: all 0.15s;
            opacity: 0.4;
        }

        .code-wrapper:hover .copy-btn,
        .copy-btn:focus {
            opacity: 1;
        }

        .copy-btn:active {
            background-color: var(--copy-btn-hover);
        }

        .copy-btn.copied {
            color: var(--copy-success-color);
            border-color: var(--copy-success-color);
        }

        .copy-btn.copied::after {
            content: "✓";
        }

        .copy-btn:not(.copied)::after {
            content: "📋";
        }

        a {
            color: var(--text-color);
            text-decoration: none;
            pointer-events: none;
        }

        blockquote {
            margin: 0;
            padding: 0 1em;
            color: var(--blockquote-color);
            border-left: 0.25em solid var(--border-color);
        }

        table {
            border-collapse: collapse;
            width: 100%;
            min-width: 800px;
        }

        table th, table td {
            border: 1px solid var(--border-color);
            padding: 4px 8px;
        }

        table tr:nth-child(2n) {
            background-color: var(--code-bg);
        }

        hr {
            border: none;
            border-top: 1px solid var(--border-color);
            margin: 12px 0;
        }

        h1, h2, h3, h4, h5, h6 {
            margin-top: 16px;
            margin-bottom: 8px;
            font-weight: 600;
            line-height: 1.25;
        }

        h1 { font-size: 2em; border-bottom: 1px solid var(--border-color); padding-bottom: 0.3em; }
        h2 { font-size: 1.5em; border-bottom: 1px solid var(--border-color); padding-bottom: 0.3em; }
        h3 { font-size: 1.25em; }
        h4 { font-size: 1em; }
        h5 { font-size: 0.875em; }
        h6 { font-size: 0.85em; color: var(--blockquote-color); }

        ul, ol {
            padding-left: 2em;
            margin: 4px 0;
        }

        li + li {
            margin-top: 4px;
        }

        ::-webkit-scrollbar {
            width: 8px;
            height: 8px;
        }

        ::-webkit-scrollbar-track {
            background: var(--bg-color);
        }

        ::-webkit-scrollbar-thumb {
            background: var(--border-color);
            border-radius: 4px;
        }

        ::-webkit-scrollbar-thumb:hover {
            background: var(--blockquote-color);
        }
    </style>
</head>
<body>
    <div class="content $viewClass" id="content-area">
        <pre><code id="content"$languageClass>${escapedContent}</code></pre>
    </div>
    <script>
        (function() {
            var isDarkTheme = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
            if (isDarkTheme) {
                document.documentElement.setAttribute('data-theme', 'dark');
            } else {
                document.documentElement.setAttribute('data-theme', 'light');
            }

            function addCopyButtons() {
                document.querySelectorAll('pre code').forEach(function(codeBlock) {
                    var pre = codeBlock.parentElement;
                    if (pre.parentElement && pre.parentElement.classList.contains('code-wrapper')) {
                        return;
                    }

                    var wrapper = document.createElement('div');
                    wrapper.className = 'code-wrapper';
                    pre.parentNode.insertBefore(wrapper, pre);
                    wrapper.appendChild(pre);

                    var btn = document.createElement('button');
                    btn.className = 'copy-btn';
                    btn.setAttribute('aria-label', '复制代码');
                    btn.setAttribute('tabindex', '0');
                    wrapper.appendChild(btn);

                    btn.addEventListener('click', function(e) {
                        e.preventDefault();
                        e.stopPropagation();

                        var code = codeBlock.textContent || codeBlock.innerText;
                        var textarea = document.createElement('textarea');
                        textarea.value = code;
                        textarea.style.position = 'fixed';
                        textarea.style.left = '-9999px';
                        textarea.style.top = '0';
                        document.body.appendChild(textarea);
                        textarea.select();
                        textarea.setSelectionRange(0, 99999);

                        try {
                            document.execCommand('copy');
                            btn.classList.add('copied');
                            setTimeout(function() {
                                btn.classList.remove('copied');
                            }, 2000);
                        } catch (err) {
                            navigator.clipboard.writeText(code).then(function() {
                                btn.classList.add('copied');
                                setTimeout(function() {
                                    btn.classList.remove('copied');
                                }, 2000);
                            });
                        }

                        document.body.removeChild(textarea);
                    });
                });
            }

            function loadScript(url, onload) {
                var script = document.createElement('script');
                script.src = url;
                script.onload = onload;
                document.head.appendChild(script);
            }

            var isMarkdown = $isMarkdown;
            var content = document.getElementById('content').textContent;

            loadScript('https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js', function() {
                if (isMarkdown) {
                    loadScript('https://cdn.jsdelivr.net/npm/markdown-it@14/dist/markdown-it.min.js', function() {
                        $markdownScript
                    });
                } else {
                    $markdownScript
                }
            });
        })();
    </script>
</body>
</html>
    """.trimIndent()
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
