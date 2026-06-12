@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package info.loveyu.mfca

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
        "html" -> htmlToPreviewHtml(r.content, isDark)
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
