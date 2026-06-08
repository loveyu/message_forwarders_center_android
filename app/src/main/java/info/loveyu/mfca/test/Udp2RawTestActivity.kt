@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.test

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.loveyu.mfca.R
import info.loveyu.mfca.plugin.PluginManager
import info.loveyu.mfca.plugin.Udp2RawPluginCore
import info.loveyu.mfca.ui.theme.MfcaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class Udp2RawTestActivity : ComponentActivity() {

    companion object {
        const val PORT_ECHO = 14181
        const val PORT_SERVER_RAW = 14182
        const val PORT_CLIENT_UDP = 14183
        const val TUNNEL_KEY = "flowgate-test-2024"
        const val PREFS_NAME = "udp2raw_test_prefs"
        const val PREF_PLUGIN_URL = "plugin_url"
        const val PREF_PROXY_URL = "proxy_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MfcaTheme { Udp2RawTestScreen(onBack = { finish() }) } }
    }
}

// ── Step model ────────────────────────────────────────────────────────────────

private enum class StepStatus { IDLE, RUNNING, SUCCESS, FAILED }

private data class TestStep(val label: String, var status: StepStatus = StepStatus.IDLE)

// ── UI ────────────────────────────────────────────────────────────────────────

@Composable
private fun Udp2RawTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
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
    var isRunning by remember { mutableStateOf(false) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var overallProgress by remember { mutableFloatStateOf(0f) }

    val logs = remember { mutableStateListOf<String>() }
    val steps = remember {
        mutableStateListOf(
            TestStep("下载 / 验证插件"),
            TestStep("绑定测试服务"),
            TestStep("启动服务端 (另一进程)"),
            TestStep("启动 udp2raw 客户端"),
            TestStep("发送 UDP Echo 数据"),
            TestStep("验证结果"),
            TestStep("清理资源"),
        )
    }
    val logListState = rememberLazyListState()

    // Auto-scroll on new log entry
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
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // URL input
            OutlinedTextField(
                value = pluginUrl,
                onValueChange = { pluginUrl = it },
                label = { Text("插件地址（支持 .so/.zip/.gz 及 data://sdcard:// 等）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isRunning,
            )

            // Proxy input
            OutlinedTextField(
                value = proxyUrl,
                onValueChange = { proxyUrl = it },
                label = { Text("下载代理（可选，如 socks5://127.0.0.1:1080）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isRunning,
            )

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        prefs.edit()
                            .putString(Udp2RawTestActivity.PREF_PLUGIN_URL, pluginUrl)
                            .putString(Udp2RawTestActivity.PREF_PROXY_URL, proxyUrl)
                            .apply()
                        resetAll()
                        isRunning = true
                        testJob = scope.launch {
                            try {
                                runTest(
                                    context = context,
                                    pluginUrl = pluginUrl,
                                    proxyUrl = proxyUrl.ifBlank { null },
                                    addLog = { addLog(it) },
                                    setStep = { i, s -> setStep(i, s) },
                                )
                            } catch (_: CancellationException) {
                                addLog("⚠️ 测试已取消")
                            } finally {
                                isRunning = false
                                testJob = null
                            }
                        }
                    },
                    enabled = !isRunning && pluginUrl.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("开始测试")
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
                }
            }

            // Overall progress
            LinearProgressIndicator(
                progress = { overallProgress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )

            // Step list
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                steps.forEach { step -> StepRow(step) }
            }

            // Log output
            Text("日志输出", style = MaterialTheme.typography.titleSmall)
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
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
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

// ── Test logic (suspend) ──────────────────────────────────────────────────────

private suspend fun runTest(
    context: Context,
    pluginUrl: String,
    proxyUrl: String?,
    addLog: (String) -> Unit,
    setStep: (Int, StepStatus) -> Unit,
) {
    val clientPlugin = Udp2RawPluginCore()
    var serviceConn: ServiceConnection? = null
    var serviceMessenger: Messenger? = null
    var cleanedUp = false

    try {
        // ── Step 0: Download / verify plugin ──────────────────────────────────
        setStep(0, StepStatus.RUNNING)
        val soPath: String =
            try {
                withContext(Dispatchers.IO) {
                    addLog("正在检查插件…")
                    val installed = PluginManager.isInstalled(context, "udp2raw")
                    if (installed) {
                        addLog("插件已缓存，跳过下载")
                    } else {
                        addLog("正在下载插件: $pluginUrl")
                        PluginManager.installPlugin(context, "udp2raw", pluginUrl, proxyUrl)
                        addLog("插件下载完成")
                    }
                    PluginManager.getInstalledPath(context, "udp2raw").absolutePath
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("❌ 插件获取失败: ${e.message}")
                setStep(0, StepStatus.FAILED)
                return
            }
        addLog("插件路径: $soPath")
        setStep(0, StepStatus.SUCCESS)

        // ── Step 1: Bind helper service ────────────────────────────────────────
        setStep(1, StepStatus.RUNNING)
        val serverReadyDeferred = CompletableDeferred<Unit>()
        val serverErrorDeferred = CompletableDeferred<String>()

        val incomingHandler =
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        Udp2RawTestHelperService.MSG_LOG -> addLog(msg.data.getString("text", ""))
                        Udp2RawTestHelperService.MSG_SERVER_READY ->
                            serverReadyDeferred.complete(Unit)
                        Udp2RawTestHelperService.MSG_ERROR -> {
                            val err = msg.data.getString("error", "未知错误")
                            serverErrorDeferred.complete(err)
                            if (!serverReadyDeferred.isCompleted) {
                                serverReadyDeferred.completeExceptionally(Exception(err))
                            }
                        }
                    }
                }
            }
        val activityMessenger = Messenger(incomingHandler)

        val bindDeferred = CompletableDeferred<Messenger>()
        val conn =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    bindDeferred.complete(Messenger(binder))
                }

                override fun onServiceDisconnected(name: ComponentName) {}
            }
        serviceConn = conn

        withContext(Dispatchers.Main) {
            val intent = Intent(context, Udp2RawTestHelperService::class.java)
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }

        serviceMessenger =
            try {
                withTimeout(8_000) { bindDeferred.await() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("❌ 绑定测试服务超时")
                setStep(1, StepStatus.FAILED)
                return
            }
        setStep(1, StepStatus.SUCCESS)

        // ── Step 2: Start server-side (via service in :udp2rawtest process) ───
        setStep(2, StepStatus.RUNNING)
        val startMsg =
            Message.obtain(null, Udp2RawTestHelperService.MSG_START).apply {
                replyTo = activityMessenger
                data =
                    Bundle().apply {
                        putString("plugin_path", soPath)
                        putInt("echo_port", Udp2RawTestActivity.PORT_ECHO)
                        putInt("raw_port", Udp2RawTestActivity.PORT_SERVER_RAW)
                        putString("tunnel_key", Udp2RawTestActivity.TUNNEL_KEY)
                    }
            }
        serviceMessenger.send(startMsg)

        try {
            withTimeout(30_000) { serverReadyDeferred.await() }
        } catch (e: TimeoutCancellationException) {
            addLog("❌ 服务端启动超时（可能缺少 CAP_NET_RAW / root 权限）")
            setStep(2, StepStatus.FAILED)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("❌ 服务端错误: ${e.message}")
            setStep(2, StepStatus.FAILED)
            return
        }
        setStep(2, StepStatus.SUCCESS)

        // ── Step 3: Start udp2raw client in main process ───────────────────────
        setStep(3, StepStatus.RUNNING)
        try {
            withContext(Dispatchers.IO) {
                clientPlugin.load(soPath)
                addLog("【客户端】插件加载成功")
                val ret =
                    clientPlugin.start(
                        listOf(
                            "-c",
                            "-l127.0.0.1:${Udp2RawTestActivity.PORT_CLIENT_UDP}",
                            "-r127.0.0.1:${Udp2RawTestActivity.PORT_SERVER_RAW}",
                            "--raw-mode",
                            "faketcp",
                            "-k",
                            Udp2RawTestActivity.TUNNEL_KEY,
                        ),
                        logFile = null,
                    )
                if (ret != 0) error("start() 返回 $ret")
                Thread.sleep(2500)
                if (!clientPlugin.isRunning()) error("客户端启动后立即退出")
                addLog("【客户端】udp2raw 客户端运行中")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("❌ 客户端启动失败: ${e.message}")
            setStep(3, StepStatus.FAILED)
            return
        }
        setStep(3, StepStatus.SUCCESS)

        // ── Step 4: Send UDP echo packets ─────────────────────────────────────
        setStep(4, StepStatus.RUNNING)
        val echoResults = mutableListOf<Boolean>()
        try {
            withContext(Dispatchers.IO) {
                val socket = DatagramSocket()
                socket.soTimeout = 5_000
                val serverAddr = InetAddress.getByName("127.0.0.1")
                repeat(5) { i ->
                    val msg = "hello-flowgate-$i"
                    val sendBuf = msg.toByteArray()
                    val sendPkt =
                        DatagramPacket(
                            sendBuf,
                            sendBuf.size,
                            serverAddr,
                            Udp2RawTestActivity.PORT_CLIENT_UDP,
                        )
                    socket.send(sendPkt)
                    addLog("→ 发送: $msg")
                    val recvBuf = ByteArray(256)
                    val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                    try {
                        socket.receive(recvPkt)
                        val reply = String(recvPkt.data, 0, recvPkt.length)
                        val ok = reply == msg
                        echoResults.add(ok)
                        addLog("← 收到: $reply ${if (ok) "✓" else "✗ (不匹配)"}")
                    } catch (e: Exception) {
                        echoResults.add(false)
                        addLog("← 超时或错误: ${e.message}")
                    }
                    Thread.sleep(500)
                }
                socket.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("❌ Echo 测试异常: ${e.message}")
            setStep(4, StepStatus.FAILED)
            return
        }
        setStep(4, StepStatus.SUCCESS)

        // ── Step 5: Validate results ───────────────────────────────────────────
        setStep(5, StepStatus.RUNNING)
        val passed = echoResults.count { it }
        val total = echoResults.size
        addLog("结果: $passed/$total 成功")
        if (passed == total) {
            addLog("✅ 所有 Echo 测试通过")
            setStep(5, StepStatus.SUCCESS)
        } else {
            addLog("❌ 部分 Echo 测试失败")
            setStep(5, StepStatus.FAILED)
        }

        // ── Step 6: Cleanup ────────────────────────────────────────────────────
        setStep(6, StepStatus.RUNNING)
        doCleanup(serviceMessenger, serviceConn, context, clientPlugin, addLog)
        cleanedUp = true
        setStep(6, StepStatus.SUCCESS)
    } finally {
        if (!cleanedUp) {
            doCleanup(serviceMessenger, serviceConn, context, clientPlugin)
        }
    }
}

private fun doCleanup(
    serviceMessenger: Messenger?,
    serviceConn: ServiceConnection?,
    context: Context,
    clientPlugin: Udp2RawPluginCore,
    addLog: ((String) -> Unit)? = null,
) {
    try {
        clientPlugin.stop()
        addLog?.invoke("【客户端】udp2raw 已停止")
    } catch (_: Exception) {}
    try {
        serviceMessenger?.send(Message.obtain(null, Udp2RawTestHelperService.MSG_STOP))
    } catch (_: Exception) {}
    try {
        serviceConn?.let { context.unbindService(it) }
    } catch (_: Exception) {}
}
