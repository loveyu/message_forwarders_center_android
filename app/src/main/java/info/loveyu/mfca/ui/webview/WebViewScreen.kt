package info.loveyu.mfca.ui.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

class WebViewInterface(private val onBack: () -> Unit) {
    @android.webkit.JavascriptInterface
    fun onLinkClick(url: String) {
        if (url == "__back__") { onBack(); return }
    }

    @android.webkit.JavascriptInterface
    fun onThemeChanged(theme: String) {}

    @android.webkit.JavascriptInterface
    fun onCopy(text: String) {}
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    title: String = "",
    content: String = "",
    onBack: () -> Unit = {},
) {
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            createWebView(ctx, title, content, isDarkTheme, onBack).also { webView ->
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        isLoading = true
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        isLoading = false
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = false
                }
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        progress = newProgress
                    }
                }
            }
        },
        update = { webView ->
            if (isDarkTheme != webView.tag as? Boolean) {
                webView.tag = isDarkTheme
                webView.loadDataWithBaseURL(
                    null,
                    wrapHtml(title, content, isDarkTheme),
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
    )

    if (isLoading) {
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    ctx: android.content.Context,
    title: String,
    content: String,
    isDarkTheme: Boolean,
    onBack: () -> Unit,
): WebView {
    return WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode =
            android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        addJavascriptInterface(WebViewInterface(onBack), "Android")
        tag = isDarkTheme
        loadDataWithBaseURL(
            null,
            wrapHtml(title, content, isDarkTheme),
            "text/html",
            "UTF-8",
            null
        )
    }
}
