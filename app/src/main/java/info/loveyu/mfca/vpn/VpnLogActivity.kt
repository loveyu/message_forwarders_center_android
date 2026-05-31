package info.loveyu.mfca.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.loveyu.mfca.ui.theme.MfcaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class VpnLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MfcaTheme { VpnLogScreen(onBack = { finish() }) } }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, VpnLogActivity::class.java)
    }
}

private data class LogLine(val text: String, val isStderr: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VpnLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var isRunning by remember { mutableStateOf(false) }
    var hasEverStarted by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Poll log files every second
    LaunchedEffect(Unit) {
        while (isActive) {
            val logFiles = withContext(Dispatchers.IO) { MihomoProcessManager.getLastLogFiles() }
            isRunning = MihomoProcessManager.isRunning()
            hasEverStarted = logFiles != null
            if (logFiles != null) {
                val (stdoutFile, stderrFile) = logFiles
                val combined = mutableListOf<LogLine>()
                withContext(Dispatchers.IO) {
                    runCatching { stdoutFile.readLines() }.getOrDefault(emptyList())
                        .filter { it.isNotBlank() }
                        .mapTo(combined) { LogLine(it, isStderr = false) }
                    runCatching { stderrFile.readLines() }.getOrDefault(emptyList())
                        .filter { it.isNotBlank() }
                        .mapTo(combined) { LogLine(it, isStderr = true) }
                }
                lines = combined
            }
            delay(1000)
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("mihomo 日志")
                        Text(
                            text = if (isRunning) "● 运行中" else "已停止",
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (isRunning) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (lines.isEmpty()) {
                                Toast.makeText(context, "暂无日志", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            val text = lines.joinToString("\n") { it.text }
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("mihomo_logs", text))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text("复制")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (lines.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (!hasEverStarted) "VPN 尚未启动过" else "日志为空",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = innerPadding,
                modifier =
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                items(
                    count = lines.size,
                    key = { it },
                ) { index ->
                    val line = lines[index]
                    Text(
                        text = line.text,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                        color =
                            if (line.isStderr) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                        softWrap = true,
                        modifier =
                            Modifier.fillMaxWidth().padding(vertical = 1.dp).pointerInput(index) {
                                detectTapGestures {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("log_line", line.text))
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                }
                            },
                    )
                }
            }
        }
    }
}
