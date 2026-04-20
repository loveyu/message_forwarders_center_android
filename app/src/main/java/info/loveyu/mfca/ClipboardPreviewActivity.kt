@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package info.loveyu.mfca

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.viewinterop.AndroidView
import info.loveyu.mfca.clipboard.ClipboardHistoryDbHelper
import info.loveyu.mfca.clipboard.ClipboardRecord
import info.loveyu.mfca.ui.theme.MfcaTheme

class ClipboardPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recordId = intent.getLongExtra("record_id", -1)
        setContent {
            MfcaTheme {
                ClipboardPreviewScreen(recordId = recordId, onBack = { finish() })
            }
        }
    }

    companion object {
        fun start(context: Context, recordId: Long) {
            context.startActivity(
                Intent(context, ClipboardPreviewActivity::class.java).apply {
                    putExtra("record_id", recordId)
                }
            )
        }

        fun hasPreview(contentType: String): Boolean {
            return contentType in setOf("html", "markdown", "json", "yaml")
        }
    }
}

@Composable
private fun ClipboardPreviewScreen(recordId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val dbHelper = remember { ClipboardHistoryDbHelper(context) }
    var record by remember { mutableStateOf<ClipboardRecord?>(null) }

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
                    title = { Text("预览") },
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
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        }
        return
    }

    val isDark = isSystemInDarkTheme()
    val previewHtml = when (r.contentType) {
        "html" -> r.content
        "json" -> codeToHtml(prettifyJson(r.content), "json", isDark)
        "yaml" -> codeToHtml(r.content, "yaml", isDark)
        else -> markdownToHtml(r.content, isDark)
    }

    val enableJs = r.contentType in setOf("json", "yaml")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (r.contentType) {
                            "html" -> "HTML 预览"
                            "json" -> "JSON 预览"
                            "yaml" -> "YAML 预览"
                            else -> "Markdown 预览"
                        },
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
                }
            )
        }
    ) { padding ->
        AndroidView(
            factory = { ctx ->
                createPreviewWebView(ctx, previewHtml, enableJs)
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

// --- WebView factory ---

@SuppressLint("SetJavaScriptEnabled")
private fun createPreviewWebView(
    context: Context,
    htmlContent: String,
    enableJs: Boolean = false
): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        settings.apply {
            javaScriptEnabled = enableJs
            domStorageEnabled = false
        }
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
    }
}

// --- JSON prettify ---

private fun prettifyJson(content: String): String {
    return try {
        val trimmed = content.trim()
        if (trimmed.startsWith('{')) {
            org.json.JSONObject(trimmed).toString(2)
        } else {
            org.json.JSONArray(trimmed).toString(2)
        }
    } catch (_: Exception) {
        content
    }
}

// --- Code (JSON/YAML) to highlighted HTML ---

private const val HLJS_CDN = "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0"

private fun codeToHtml(code: String, language: String, isDark: Boolean): String {
    val theme = if (isDark) "github-dark-dimmed" else "github"
    val bgColor = if (isDark) "#0d1117" else "#ffffff"
    val textColor = if (isDark) "#c9d1d9" else "#333333"
    val escaped = code.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <link rel="stylesheet" href="$HLJS_CDN/styles/$theme.min.css">
            <style>
                body {
                    font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, monospace;
                    font-size: 13px;
                    line-height: 1.5;
                    background-color: $bgColor;
                    color: $textColor;
                    margin: 0;
                    padding: 0;
                }
                pre {
                    margin: 0;
                    padding: 12px;
                    background-color: transparent;
                }
                code {
                    font-family: inherit;
                    font-size: inherit;
                }
            </style>
        </head>
        <body>
            <pre><code class="language-$language">$escaped</code></pre>
            <script src="$HLJS_CDN/highlight.min.js"></script>
            <script>hljs.highlightAll();</script>
        </body>
        </html>
    """.trimIndent()
}

// --- Markdown to HTML ---

private fun markdownToHtml(markdown: String, isDark: Boolean): String {
    val bgColor = if (isDark) "#0d1117" else "#ffffff"
    val textColor = if (isDark) "#c9d1d9" else "#333333"
    val codeBg = if (isDark) "#161b22" else "#f6f8fa"
    val borderColor = if (isDark) "#30363d" else "#d0d7de"

    val codeBlocks = mutableListOf<String>()
    var processed = markdown.replace(Regex("```[\\w]*\\n([\\s\\S]*?)```")) { match ->
        val placeholder = "\u0000CODE${codeBlocks.size}\u0000"
        codeBlocks.add(match.value)
        placeholder
    }

    processed = processed
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    processed = processed.replace(Regex("^(#{6})\\s+(.+)$", RegexOption.MULTILINE), "<h6>$2</h6>")
    processed = processed.replace(Regex("^(#{5})\\s+(.+)$", RegexOption.MULTILINE), "<h5>$2</h5>")
    processed = processed.replace(Regex("^(#{4})\\s+(.+)$", RegexOption.MULTILINE), "<h4>$2</h4>")
    processed = processed.replace(Regex("^(#{3})\\s+(.+)$", RegexOption.MULTILINE), "<h3>$2</h3>")
    processed = processed.replace(Regex("^(#{2})\\s+(.+)$", RegexOption.MULTILINE), "<h2>$2</h2>")
    processed = processed.replace(Regex("^(#{1})\\s+(.+)$", RegexOption.MULTILINE), "<h1>$2</h1>")

    processed = processed.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
    processed = processed.replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
    processed = processed.replace(Regex("`(.+?)`"), "<code>$1</code>")
    processed = processed.replace(Regex("\\[(.+?)\\]\\((.+?)\\)"), "<a href=\"$2\">$1</a>")
    processed = processed.replace(
        Regex("^&gt;\\s+(.+)$", RegexOption.MULTILINE),
        "<blockquote>$1</blockquote>"
    )
    processed = processed.replace(Regex("^[-*+]\\s+(.+)$", RegexOption.MULTILINE), "<li>$1</li>")

    for ((index, block) in codeBlocks.withIndex()) {
        val placeholder = "\u0000CODE$index\u0000"
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
