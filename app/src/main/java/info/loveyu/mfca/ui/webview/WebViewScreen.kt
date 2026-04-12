package info.loveyu.mfca.ui.webview

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private const val CDN_HIGHLIGHT_JS = "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0"
private const val CDN_MARKED_JS = "https://cdn.jsdelivr.net/npm/marked@9.1.6"

class WebViewInterface(private val onBack: () -> Unit) {
    @JavascriptInterface
    fun navigateBack() {
        onBack()
    }
}

@Composable
fun WebViewScreen(
    title: String,
    htmlContent: String,
    isDarkTheme: Boolean,
    onBack: () -> Unit
) {
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler {
        if (canGoBack) {
            canGoBack = false
        } else {
            onBack()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            createWebView(context, title, htmlContent, isDarkTheme, onBack) { needsBack ->
                canGoBack = needsBack
            }.also { webView ->
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        canGoBack = view?.canGoBack() == true
                    }
                }
            }
        },
        update = { webView ->
            webView.evaluateJavascript(
                generateThemeSwitchScript(isDarkTheme),
                null
            )
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: android.content.Context,
    title: String,
    content: String,
    isDarkTheme: Boolean,
    onBack: () -> Unit,
    onNavigationChanged: (Boolean) -> Unit
): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
        }
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addJavascriptInterface(WebViewInterface(onBack), "Android")
        loadDataWithBaseURL(
            null,
            wrapHtml(title, content, isDarkTheme),
            "text/html",
            "UTF-8",
            null
        )
    }
}

private fun generateThemeSwitchScript(isDarkTheme: Boolean): String {
    val theme = if (isDarkTheme) "dark" else "light"
    return "document.body.setAttribute('data-theme', '$theme');"
}

private fun wrapHtml(title: String, content: String, isDarkTheme: Boolean): String {
    val escapedContent = content
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("$", "\\$")

    val escapedTitle = title
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("$", "\\$")
        .replace("'", "\\'")
        .replace("\n", "\\n")

    val themeClass = if (isDarkTheme) "dark" else "light"

    return """
<!DOCTYPE html>
<html data-theme="$themeClass">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <link rel="stylesheet" href="$CDN_HIGHLIGHT_JS/styles/github.min.css">
    <script src="$CDN_HIGHLIGHT_JS/highlight.min.js"></script>
    <script src="$CDN_MARKED_JS/lib/marked.umd.js"></script>
    <style>
        :root {
            --bg-color: #ffffff;
            --text-color: #333333;
            --code-bg: #f6f8fa;
            --border-color: #d0d7de;
            --link-color: #0969da;
            --blockquote-color: #656d76;
            --header-bg: #f6f8fa;
            --header-border: #d0d7de;
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
            --header-bg: #161b22;
            --header-border: #30363d;
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
            /* 允许文本选中 */
            -webkit-user-select: text;
            user-select: text;
        }

        body {
            display: flex;
            flex-direction: column;
            background-color: var(--bg-color);
            color: var(--text-color);
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
            font-size: 14px;
            line-height: 1.6;
        }

        .header {
            display: flex;
            align-items: center;
            padding: 12px 16px;
            background-color: var(--header-bg);
            border-bottom: 1px solid var(--header-border);
            flex-shrink: 0;
            -webkit-backdrop-filter: blur(10px);
        }

        .back-btn {
            display: flex;
            align-items: center;
            justify-content: center;
            width: 36px;
            height: 36px;
            margin-right: 12px;
            background: transparent;
            border: none;
            border-radius: 8px;
            cursor: pointer;
            color: var(--link-color);
            font-size: 20px;
            transition: background-color 0.15s;
        }

        .back-btn:active {
            background-color: var(--border-color);
        }

        .title {
            flex: 1;
            font-size: 18px;
            font-weight: 600;
            color: var(--text-color);
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }

        .content {
            flex: 1;
            overflow-y: auto;
            padding: 16px;
            -webkit-overflow-scrolling: touch;
        }

        /* 代码块容器 */
        .code-wrapper {
            position: relative;
            margin: 8px 0;
        }

        pre {
            background-color: var(--code-bg) !important;
            border: 1px solid var(--border-color);
            border-radius: 6px;
            padding: 12px !important;
            padding-right: 48px !important;
            overflow-x: auto;
            margin: 0;
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

        /* 复制按钮 */
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
            opacity: 0;
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
            color: var(--link-color);
            text-decoration: none;
        }

        a:hover {
            text-decoration: underline;
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

        ul, ol {
            padding-left: 2em;
            margin: 8px 0;
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
    <div class="header">
        <button class="back-btn" id="backBtn" onclick="Android.navigateBack()" aria-label="返回">←</button>
        <div class="title" id="title">$escapedTitle</div>
    </div>
    <div class="content">
        <pre><code id="content">$escapedContent</code></pre>
    </div>
    <script>
        (function() {
            function addCopyButtons() {
                document.querySelectorAll('pre code').forEach(function(codeBlock) {
                    var pre = codeBlock.parentElement;
                    // 避免重复添加
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
                            // fallback: 使用剪贴板 API
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

            var content = document.getElementById('content').textContent;
            var isMarkdown = /^[#*`\-\[\]|!]/.test(content.trim()) ||
                             content.includes('##') ||
                             content.includes('**') ||
                             content.includes('[]:');

            if (isMarkdown) {
                document.getElementById('content').innerHTML = marked.parse(content);
                document.querySelectorAll('pre code').forEach(function(block) {
                    hljs.highlightElement(block);
                });
                // Markdown 渲染后添加复制按钮
                setTimeout(addCopyButtons, 0);
            } else {
                hljs.highlightElement(document.getElementById('content'));
                // 代码块添加复制按钮
                setTimeout(addCopyButtons, 0);
            }

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
