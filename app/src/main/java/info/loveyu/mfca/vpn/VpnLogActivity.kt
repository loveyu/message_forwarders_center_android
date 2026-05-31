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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.ui.theme.LogDebugColor
import info.loveyu.mfca.ui.theme.LogErrorColor
import info.loveyu.mfca.ui.theme.LogInfoColor
import info.loveyu.mfca.ui.theme.LogWarnColor
import info.loveyu.mfca.ui.theme.MfcaTheme
import info.loveyu.mfca.util.LogLevel
import info.loveyu.mfca.util.LogManager

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VpnLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val allLogs by LogManager.logs.collectAsState()
    val vpnLogs by remember { derivedStateOf { allLogs.filter { it.tag == "VPN" } } }
    val listState = rememberLazyListState()

    LaunchedEffect(vpnLogs.size) {
        if (vpnLogs.isNotEmpty()) listState.animateScrollToItem(vpnLogs.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VPN 日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (vpnLogs.isEmpty()) {
                                Toast.makeText(context, "暂无日志", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            val text = vpnLogs.joinToString("\n") { it.formatted }
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("vpn_logs", text))
                            Toast.makeText(context, "已复制全部 VPN 日志", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text("复制")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (vpnLogs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无 VPN 日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = innerPadding,
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                items(vpnLogs, key = { it.id }) { entry ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier =
                            Modifier.fillMaxWidth().padding(vertical = 2.dp).pointerInput(entry.id) {
                                detectTapGestures(
                                    onTap = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("vpn_log", entry.formatted))
                                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                    },
                                )
                            },
                    ) {
                        Column {
                            Text(
                                text = entry.formatted,
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    when (entry.level) {
                                        LogLevel.ERROR -> LogErrorColor
                                        LogLevel.WARN -> LogWarnColor
                                        LogLevel.INFO -> LogInfoColor
                                        LogLevel.DEBUG -> LogDebugColor
                                    },
                                softWrap = true,
                            )
                        }
                    }
                }
            }
        }
    }
}
