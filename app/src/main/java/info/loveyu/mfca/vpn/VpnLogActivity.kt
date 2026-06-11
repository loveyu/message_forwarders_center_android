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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.loveyu.mfca.R
import info.loveyu.mfca.ui.theme.MfcaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VpnLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MfcaTheme { VpnLogScreen(onBack = { finish() }) } }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, VpnLogActivity::class.java)

        fun exportDiagnostics(context: Context): String {
            val extDir = File(context.getExternalFilesDir(null), "vpn_debug").apply { mkdirs() }
            val cacheVpnDir = File(context.cacheDir, "vpn")
            val files = mutableListOf<Pair<File, String>>()

            val profilesDir = File(context.filesDir, "vpn/profiles")
            if (profilesDir.exists()) {
                profilesDir.listFiles()?.forEach { f ->
                    if (f.name.endsWith(".runtime.yaml")) {
                        files.add(f to "profiles/${f.name}")
                    }
                }
            }

            if (cacheVpnDir.exists()) {
                cacheVpnDir.listFiles()?.forEach { candidateDir ->
                    if (candidateDir.isDirectory) {
                        File(candidateDir, "m2m.stdout.log").takeIf { it.exists() }?.let {
                            files.add(it to "${candidateDir.name}/m2m.stdout.log")
                        }
                        File(candidateDir, "m2m.stderr.log").takeIf { it.exists() }?.let {
                            files.add(it to "${candidateDir.name}/m2m.stderr.log")
                        }
                        val bridgeDir = File(candidateDir, "bridge")
                        if (bridgeDir.exists()) {
                            File(bridgeDir, "bridge.stdout.log").takeIf { it.exists() }?.let {
                                files.add(it to "${candidateDir.name}/bridge/bridge.stdout.log")
                            }
                            File(bridgeDir, "bridge.stderr.log").takeIf { it.exists() }?.let {
                                files.add(it to "${candidateDir.name}/bridge/bridge.stderr.log")
                            }
                        }
                    }
                }
            }

            val configCacheDir = File(context.filesDir, "vpn/config_cache")
            if (configCacheDir.exists()) {
                configCacheDir.listFiles()?.forEach { f ->
                    if (f.name.endsWith(".yaml")) {
                        files.add(f to "config_cache/${f.name}")
                    }
                }
            }

            val appLogsDir = File(context.cacheDir, "logs")
            if (appLogsDir.exists()) {
                appLogsDir.listFiles()?.forEach { f ->
                    if (f.isFile) {
                        files.add(f to "app_logs/${f.name}")
                    }
                }
            }

            var count = 0
            files.forEach { (src, relPath) ->
                val target = File(extDir, relPath)
                target.parentFile?.mkdirs()
                src.copyTo(target, overwrite = true)
                count++
            }
            return "已导出 $count 个文件到 ${extDir.absolutePath}"
        }
    }
}

private data class LogLine(val text: String, val isStderr: Boolean)

private enum class LogSource(
    val label: String,
) {
    M2M("m2m"),
    BRIDGE("Bridge"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VpnLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tabIndex by remember { mutableIntStateOf(0) }
    val sources = remember { LogSource.entries }
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var isRunning by remember { mutableStateOf(false) }
    var hasEverStarted by remember { mutableStateOf(false) }
    var showSourceMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(tabIndex) {
        while (isActive) {
            val source = sources[tabIndex]
            val logFiles =
                withContext(Dispatchers.IO) {
                    when (source) {
                        LogSource.M2M -> M2mProcessManager.getLastLogFiles()
                        LogSource.BRIDGE -> VpnBridgeProcessManager.getLastLogFiles()
                    }
                }
            isRunning =
                when (source) {
                    LogSource.M2M -> M2mProcessManager.isRunning()
                    LogSource.BRIDGE -> VpnBridgeProcessManager.isRunning()
                }
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
            } else {
                lines = emptyList()
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
                        Text("${sources[tabIndex].label} 日志")
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
                            cm.setPrimaryClip(ClipData.newPlainText("vpn_logs", text))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text("复制")
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = null,
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("切换日志来源") },
                                onClick = {
                                    showOverflowMenu = false
                                    showSourceMenu = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("清空日志") },
                                onClick = {
                                    showOverflowMenu = false
                                    lines = emptyList()
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            val source = sources[tabIndex]
                                            val logFiles =
                                                when (source) {
                                                    LogSource.M2M -> M2mProcessManager.getLastLogFiles()
                                                    LogSource.BRIDGE -> VpnBridgeProcessManager.getLastLogFiles()
                                                }
                                            if (logFiles != null) {
                                                logFiles.first.writeText("")
                                                logFiles.second.writeText("")
                                            }
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.vpn_export_diagnostics)) },
                                onClick = {
                                    showOverflowMenu = false
                                    val msg = VpnLogActivity.exportDiagnostics(context)
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                },
                            )
                        }
                    }
                    // Source switch sub-menu (rendered as a separate popup)
                    DropdownMenu(
                        expanded = showSourceMenu,
                        onDismissRequest = { showSourceMenu = false },
                    ) {
                        sources.forEachIndexed { index, source ->
                            DropdownMenuItem(
                                text = {
                                    androidx.compose.foundation.layout.Row(
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = tabIndex == index,
                                            onClick = null,
                                        )
                                        Text(
                                            text = source.label,
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    }
                                },
                                onClick = {
                                    tabIndex = index
                                    showSourceMenu = false
                                },
                            )
                        }
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
