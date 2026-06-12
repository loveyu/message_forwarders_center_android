@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.test.udp2raw

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.loveyu.mfca.R
import info.loveyu.mfca.ui.theme.MfcaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class Udp2RawTestActivity : ComponentActivity() {

    companion object {
        const val PORT_ECHO = 14181
        const val PORT_SERVER_RAW = 14182
        const val PORT_CLIENT_UDP = 14183
        const val TUNNEL_KEY = "flowgate-test-2024"
        const val PREFS_NAME = "udp2raw_test_prefs"
        const val PREF_PLUGIN_URL = "plugin_url"
        const val PREF_PROXY_URL = "proxy_url"
        const val PREF_RAW_MODE = "raw_mode"
        val RAW_MODES = listOf("faketcp", "udp", "icmp")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MfcaTheme { Udp2RawTestScreen(onBack = { finish() }) } }
    }
}

@Composable
private fun Udp2RawTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val prefs = remember {
        context.getSharedPreferences(Udp2RawTestActivity.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    var pluginUrl by remember {
        mutableStateOf(prefs.getString(Udp2RawTestActivity.PREF_PLUGIN_URL, "") ?: "")
    }
    var proxyUrl by remember {
        mutableStateOf(prefs.getString(Udp2RawTestActivity.PREF_PROXY_URL, "") ?: "")
    }
    var rawMode by remember {
        mutableStateOf(
            prefs.getString(Udp2RawTestActivity.PREF_RAW_MODE, "faketcp") ?: "faketcp"
        )
    }
    var isRunning by remember { mutableStateOf(false) }
    var testJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var overallProgress by remember { mutableFloatStateOf(0f) }

    val logs = remember { mutableStateListOf<String>() }
    val steps = remember {
        mutableStateListOf(
            TestStep("下载 / 验证插件"),
            TestStep("Java 直连 UDP 测试"),
            TestStep("绑定测试服务"),
            TestStep("启动服务端 (另一进程)"),
            TestStep("启动 udp2raw 客户端"),
            TestStep("发送 UDP Echo 数据"),
            TestStep("验证结果"),
            TestStep("清理资源"),
        )
    }
    val logListState = rememberLazyListState()
    val stepListState = rememberLazyListState()

    val activeStepIndex by remember {
        derivedStateOf {
            val running = steps.indexOfFirst { it.status == StepStatus.RUNNING }
            if (running >= 0) return@derivedStateOf running
            val failed = steps.indexOfFirst { it.status == StepStatus.FAILED }
            if (failed >= 0) return@derivedStateOf failed
            val lastSuccess = steps.indexOfLast { it.status == StepStatus.SUCCESS }
            if (lastSuccess >= 0) lastSuccess + 1 else 0
        }
    }

    LaunchedEffect(activeStepIndex) {
        val target =
            (activeStepIndex - 1).coerceAtLeast(0).coerceAtMost(maxOf(0, steps.size - 3))
        stepListState.animateScrollToItem(target)
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) logListState.animateScrollToItem(logs.size - 1)
    }

    fun addLog(text: String) {
        logs.add(text)
    }

    fun setStep(idx: Int, status: StepStatus) {
        steps[idx] = steps[idx].copy(status = status)
        val done = steps.count { it.status == StepStatus.SUCCESS }
        overallProgress = done.toFloat() / steps.size
    }

    fun resetAll() {
        steps.forEachIndexed { i, _ -> steps[i] = steps[i].copy(status = StepStatus.IDLE) }
        logs.clear()
        overallProgress = 0f
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.test_udp2raw_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = pluginUrl,
                onValueChange = { pluginUrl = it },
                label = { Text("插件地址（支持 .so/.zip/.gz 及 data://sdcard:// 等）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isRunning,
            )

            OutlinedTextField(
                value = proxyUrl,
                onValueChange = { proxyUrl = it },
                label = { Text("下载代理（可选，如 socks5://127.0.0.1:1080）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isRunning,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var modeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = modeExpanded,
                    onExpandedChange = { modeExpanded = !modeExpanded && !isRunning },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = rawMode,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Raw Mode") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded)
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        enabled = !isRunning,
                    )
                    ExposedDropdownMenu(
                        expanded = modeExpanded,
                        onDismissRequest = { modeExpanded = false },
                    ) {
                        Udp2RawTestActivity.RAW_MODES.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        mode +
                                            when (mode) {
                                                "faketcp" -> "  (需 root)"
                                                "icmp" -> "  (需 root)"
                                                "udp" -> "  (需 iptables/CAP_NET_RAW)"
                                                else -> ""
                                            }
                                    )
                                },
                                onClick = {
                                    rawMode = mode
                                    modeExpanded = false
                                },
                            )
                        }
                    }
                }

                if (isRunning) {
                    Button(
                        onClick = { testJob?.cancel() },
                        modifier = Modifier.weight(1f),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                    ) {
                        Text("取消测试")
                    }
                } else {
                    Button(
                        onClick = {
                            prefs.edit()
                                .putString(Udp2RawTestActivity.PREF_PLUGIN_URL, pluginUrl)
                                .putString(Udp2RawTestActivity.PREF_PROXY_URL, proxyUrl)
                                .putString(Udp2RawTestActivity.PREF_RAW_MODE, rawMode)
                                .apply()
                            resetAll()
                            isRunning = true
                            testJob = scope.launch {
                                try {
                                    runUdp2RawTest(
                                        context = context,
                                        pluginUrl = pluginUrl,
                                        proxyUrl = proxyUrl.ifBlank { null },
                                        rawMode = rawMode,
                                        addLog = { addLog(it) },
                                        setStep = { i, s -> setStep(i, s) },
                                    )
                                } catch (_: CancellationException) {
                                    addLog("⚠️ 测试已取消")
                                } finally {
                                    steps.forEachIndexed { i, step ->
                                        if (step.status == StepStatus.RUNNING) {
                                            steps[i] = step.copy(status = StepStatus.IDLE)
                                        }
                                    }
                                    isRunning = false
                                    testJob = null
                                }
                            }
                        },
                        enabled = pluginUrl.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("开始测试")
                    }
                }
            }

            LinearProgressIndicator(
                progress = { overallProgress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )

            LazyColumn(
                state = stepListState,
                modifier = Modifier.fillMaxWidth().height(88.dp),
                userScrollEnabled = false,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(steps) { step -> StepRow(step) }
            }

            Text(
                "日志输出（单击复制行 / 双击复制全部）",
                style = MaterialTheme.typography.titleSmall,
            )
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.shapes.small,
                        )
                        .padding(8.dp)
            ) {
                LazyColumn(state = logListState, contentPadding = PaddingValues(0.dp)) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            fontFamily = FontFamily.Monospace,
                            modifier =
                                Modifier.pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = {
                                            clipboardManager.setText(AnnotatedString(line))
                                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT)
                                                .show()
                                        },
                                        onDoubleTap = {
                                            clipboardManager.setText(
                                                AnnotatedString(logs.joinToString("\n"))
                                            )
                                            Toast.makeText(
                                                context,
                                                "已复制全部日志 (${logs.size} 行)",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: TestStep) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (step.status) {
            StepStatus.IDLE ->
                Box(
                    Modifier.size(12.dp)
                        .background(Color.Gray.copy(alpha = 0.4f), CircleShape)
                )
            StepStatus.RUNNING ->
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            StepStatus.SUCCESS ->
                Box(Modifier.size(12.dp).background(Color(0xFF4CAF50), CircleShape))
            StepStatus.FAILED ->
                Box(
                    Modifier.size(12.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = step.label,
            style = MaterialTheme.typography.bodySmall,
            color =
                when (step.status) {
                    StepStatus.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
        )
    }
}
