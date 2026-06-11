@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.test

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
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
import androidx.core.content.ContextCompat
import info.loveyu.mfca.R
import info.loveyu.mfca.plugin.PluginManager
import info.loveyu.mfca.ui.theme.MfcaTheme
import info.loveyu.mfca.util.HttpDownloader
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class M2mFullTestActivity : ComponentActivity() {

    companion object {
        const val PREFS_NAME = "m2m_test_prefs"
        const val PREF_CORE_URL = "core_url"
        const val PREF_PROXY_URL = "proxy_url"
        const val PREF_CONFIG_URL = "config_url"
        const val PREF_MIXED_PORT = "mixed_port"
        const val PREF_TEST_URL = "test_url"
        const val DEFAULT_MIXED_PORT = 2080
        const val DEFAULT_TEST_URL = "https://www.google.com"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MfcaTheme { M2mVpnTestScreen(onBack = { finish() }) } }
    }
}

// -- Step model --

private enum class M2mFullStepStatus { IDLE, RUNNING, SUCCESS, FAILED }

private data class M2mFullTestStep(val label: String, var status: M2mFullStepStatus = M2mFullStepStatus.IDLE)

// -- Settings bottom sheet --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VpnSettingsSheetContent(
    coreUrl: String,
    onCoreUrlChange: (String) -> Unit,
    proxyUrl: String,
    onProxyUrlChange: (String) -> Unit,
    configUrl: String,
    onConfigUrlChange: (String) -> Unit,
    mixedPort: String,
    onMixedPortChange: (String) -> Unit,
    testUrl: String,
    onTestUrlChange: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("测试设置", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = coreUrl,
            onValueChange = onCoreUrlChange,
            label = { Text("核心地址（.so/.zip/.gz 或 data:// 等）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = proxyUrl,
            onValueChange = onProxyUrlChange,
            label = { Text("下载代理（同时用于配置文件下载）") },
            placeholder = { Text("如 socks5://127.0.0.1:1080") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = configUrl,
            onValueChange = onConfigUrlChange,
            label = { Text("配置文件下载地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = mixedPort,
            onValueChange = onMixedPortChange,
            label = { Text("覆盖的混合端口") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = testUrl,
            onValueChange = onTestUrlChange,
            label = { Text("测试访问地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onConfirm) { Text("确定") }
        }
    }
}

// -- UI --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun M2mVpnTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val prefs = remember {
        context.getSharedPreferences(M2mFullTestActivity.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    var coreUrl by remember {
        mutableStateOf(prefs.getString(M2mFullTestActivity.PREF_CORE_URL, "") ?: "")
    }
    var proxyUrl by remember {
        mutableStateOf(prefs.getString(M2mFullTestActivity.PREF_PROXY_URL, "") ?: "")
    }
    var configUrl by remember {
        mutableStateOf(prefs.getString(M2mFullTestActivity.PREF_CONFIG_URL, "") ?: "")
    }
    var mixedPort by remember {
        mutableStateOf(
            prefs.getString(
                M2mFullTestActivity.PREF_MIXED_PORT,
                M2mFullTestActivity.DEFAULT_MIXED_PORT.toString(),
            ) ?: M2mFullTestActivity.DEFAULT_MIXED_PORT.toString()
        )
    }
    var testUrl by remember {
        mutableStateOf(
            prefs.getString(M2mFullTestActivity.PREF_TEST_URL, M2mFullTestActivity.DEFAULT_TEST_URL)
                ?: M2mFullTestActivity.DEFAULT_TEST_URL
        )
    }
    var showSettings by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var overallProgress by remember { mutableFloatStateOf(0f) }

    val logs = remember { mutableStateListOf<String>() }
    val steps = remember {
        mutableStateListOf(
            M2mFullTestStep("请求 m2m 权限"),
            M2mFullTestStep("下载 / 验证核心插件"),
            M2mFullTestStep("下载配置文件"),
            M2mFullTestStep("修改混合端口"),
            M2mFullTestStep("启动代理 + m2m（排除本应用）"),
            M2mFullTestStep("等待 m2m 就绪"),
            M2mFullTestStep("通过代理访问测试（验证全链路）"),
            M2mFullTestStep("重建 m2m（仅本应用）"),
            M2mFullTestStep("通过 m2m 直接访问"),
            M2mFullTestStep("清理资源"),
        )
    }
    val logListState = rememberLazyListState()
    val stepListState = rememberLazyListState()

    val activeStepIndex by remember {
        derivedStateOf {
            val running = steps.indexOfFirst { it.status == M2mFullStepStatus.RUNNING }
            if (running >= 0) return@derivedStateOf running
            val failed = steps.indexOfFirst { it.status == M2mFullStepStatus.FAILED }
            if (failed >= 0) return@derivedStateOf failed
            val lastSuccess = steps.indexOfLast { it.status == M2mFullStepStatus.SUCCESS }
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

    fun setStep(idx: Int, status: M2mFullStepStatus) {
        steps[idx] = steps[idx].copy(status = status)
        val done = steps.count { it.status == M2mFullStepStatus.SUCCESS }
        overallProgress = done.toFloat() / steps.size
    }

    fun resetAll() {
        steps.forEachIndexed { i, _ -> steps[i] = steps[i].copy(status = M2mFullStepStatus.IDLE) }
        logs.clear()
        overallProgress = 0f
    }

    fun saveSettings() {
        prefs.edit()
            .putString(M2mFullTestActivity.PREF_CORE_URL, coreUrl)
            .putString(M2mFullTestActivity.PREF_PROXY_URL, proxyUrl)
            .putString(M2mFullTestActivity.PREF_CONFIG_URL, configUrl)
            .putString(M2mFullTestActivity.PREF_MIXED_PORT, mixedPort)
            .putString(M2mFullTestActivity.PREF_TEST_URL, testUrl)
            .apply()
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            addLog("m2m 权限已授予")
        } else {
            addLog("m2m 权限被拒绝")
        }
    }

    val sheetState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(skipHiddenState = false)
    )

    fun openSettings() {
        showSettings = true
        scope.launch { sheetState.bottomSheetState.expand() }
    }

    fun closeSettings() {
        scope.launch { sheetState.bottomSheetState.hide() }.invokeOnCompletion {
            showSettings = false
        }
    }

    fun startTest() {
        if (coreUrl.isBlank() || configUrl.isBlank()) {
            openSettings()
            return
        }
        saveSettings()
        resetAll()
        isRunning = true
        testJob = scope.launch {
            try {
                // Step 0: Check/request VPN permission upfront
                val intent = VpnService.prepare(context)
                if (intent != null) {
                    withContext(Dispatchers.Main) {
                        vpnPermissionLauncher.launch(intent)
                    }
                    withTimeout(60_000) {
                        while (VpnService.prepare(context) != null) {
                            delay(500)
                        }
                    }
                }
                runVpnTest(
                    context = context,
                    coreUrl = coreUrl,
                    proxyUrl = proxyUrl.ifBlank { null },
                    configUrl = configUrl,
                    mixedPort = mixedPort.toIntOrNull() ?: M2mFullTestActivity.DEFAULT_MIXED_PORT,
                    testUrl = testUrl.ifBlank { M2mFullTestActivity.DEFAULT_TEST_URL },
                    addLog = { addLog(it) },
                    setStep = { i, s -> setStep(i, s) },
                )
            } catch (_: CancellationException) {
                addLog("测试已取消")
            } catch (e: Exception) {
                addLog("测试异常: ${e.message}")
            } finally {
                stopVpnService(context)
                steps.forEachIndexed { i, step ->
                    if (step.status == M2mFullStepStatus.RUNNING) {
                        steps[i] = step.copy(status = M2mFullStepStatus.IDLE)
                    }
                }
                isRunning = false
                testJob = null
            }
        }
    }

    BottomSheetScaffold(
        scaffoldState = sheetState,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.test_m2m_vpn_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        sheetContent = {
            if (showSettings) {
                VpnSettingsSheetContent(
                    coreUrl = coreUrl,
                    onCoreUrlChange = { coreUrl = it },
                    proxyUrl = proxyUrl,
                    onProxyUrlChange = { proxyUrl = it },
                    configUrl = configUrl,
                    onConfigUrlChange = { configUrl = it },
                    mixedPort = mixedPort,
                    onMixedPortChange = { mixedPort = it },
                    testUrl = testUrl,
                    onTestUrlChange = { testUrl = it },
                    onConfirm = {
                        saveSettings()
                        closeSettings()
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                        onClick = { startTest() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("开始测试")
                    }
                }
                IconButton(
                    onClick = { openSettings() },
                    enabled = !isRunning,
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = if (isRunning) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            LinearProgressIndicator(
                progress = { overallProgress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )

            LazyColumn(
                state = stepListState,
                modifier = Modifier.fillMaxWidth().height(170.dp),
                userScrollEnabled = false,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(steps) { step -> VpnStepRow(step) }
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
                                            Toast
                                                .makeText(context, "已复制", Toast.LENGTH_SHORT)
                                                .show()
                                        },
                                        onDoubleTap = {
                                            clipboardManager.setText(
                                                AnnotatedString(logs.joinToString("\n"))
                                            )
                                            Toast
                                                .makeText(
                                                    context,
                                                    "已复制全部日志 (${logs.size} 行)",
                                                    Toast.LENGTH_SHORT,
                                                )
                                                .show()
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
private fun VpnStepRow(step: M2mFullTestStep) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (step.status) {
            M2mFullStepStatus.IDLE ->
                Box(
                    Modifier.size(12.dp)
                        .background(Color.Gray.copy(alpha = 0.4f), CircleShape)
                )
            M2mFullStepStatus.RUNNING ->
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            M2mFullStepStatus.SUCCESS ->
                Box(Modifier.size(12.dp).background(Color(0xFF4CAF50), CircleShape))
            M2mFullStepStatus.FAILED ->
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
                    M2mFullStepStatus.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
        )
    }
}

// -- Test logic --

private suspend fun runVpnTest(
    context: Context,
    coreUrl: String,
    proxyUrl: String?,
    configUrl: String,
    mixedPort: Int,
    testUrl: String,
    addLog: (String) -> Unit,
    setStep: (Int, M2mFullStepStatus) -> Unit,
) {
    val workDir = File(context.filesDir, "m2m_vpn_test").apply { mkdirs() }
    var vpnStarted = false

    try {
        // -- Step 0: VPN permission (already handled before this call) --
        setStep(0, M2mFullStepStatus.SUCCESS)
        addLog("m2m 权限已就绪")

        // -- Step 1: Download / verify plugin --
        setStep(1, M2mFullStepStatus.RUNNING)
        val soPath: String =
            try {
                withContext(Dispatchers.IO) {
                    addLog("正在检查核心插件…")
                    val installed = PluginManager.isInstalledFrom(context, "m2m", coreUrl)
                    if (installed) {
                        addLog("核心插件已缓存，跳过下载")
                    } else {
                        addLog("正在下载核心插件: $coreUrl")
                        if (!proxyUrl.isNullOrBlank()) addLog("使用代理: $proxyUrl")
                        PluginManager.installPlugin(context, "m2m", coreUrl, proxyUrl)
                        addLog("核心插件下载完成")
                    }
                    val path = PluginManager.getInstalledPath(context, "m2m")
                    addLog("插件文件大小: ${path.length()} bytes, ABI: ${PluginManager.deviceAbi}")
                    path.absolutePath
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("核心插件获取失败: ${e.message}")
                setStep(1, M2mFullStepStatus.FAILED)
                return
            }
        setStep(1, M2mFullStepStatus.SUCCESS)

        // -- Step 2: Download config file --
        setStep(2, M2mFullStepStatus.RUNNING)
        val configFile =
            try {
                withContext(Dispatchers.IO) {
                    downloadConfigFile(context, configUrl, proxyUrl, workDir, addLog)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("配置文件获取失败: ${e.message}")
                setStep(2, M2mFullStepStatus.FAILED)
                return
            }
        setStep(2, M2mFullStepStatus.SUCCESS)

        // -- Step 3: Override mixed port --
        setStep(3, M2mFullStepStatus.RUNNING)
        try {
            withContext(Dispatchers.IO) {
                val original = configFile.readText()
                val modified = overrideMixedPort(original, mixedPort)
                configFile.writeText(modified)
                addLog("混合端口已覆盖为: $mixedPort")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("修改混合端口失败: ${e.message}")
            setStep(3, M2mFullStepStatus.FAILED)
            return
        }
        setStep(3, M2mFullStepStatus.SUCCESS)

        // -- Step 4: Start VPN service (exclude self) --
        setStep(4, M2mFullStepStatus.RUNNING)
        try {
            startVpnService(context, soPath, configFile.absolutePath, mixedPort, includeSelf = false)
            vpnStarted = true
            addLog("m2m 服务已启动（排除本应用模式）")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("m2m 服务启动失败: ${e.message}")
            setStep(4, M2mFullStepStatus.FAILED)
            return
        }
        setStep(4, M2mFullStepStatus.SUCCESS)

        // -- Step 5: Wait for VPN ready --
        setStep(5, M2mFullStepStatus.RUNNING)
        try {
            val vpnReady = CompletableDeferred<Unit>()
            M2mFullTestService.callback = { event ->
                when (event) {
                    is M2mFullTestService.Event.Log -> addLog(event.message)
                    is M2mFullTestService.Event.Ready -> vpnReady.complete(Unit)
                    is M2mFullTestService.Event.Error -> {
                        if (!vpnReady.isCompleted) {
                            vpnReady.completeExceptionally(Exception(event.message))
                        }
                    }
                }
            }
            withTimeout(30_000) { vpnReady.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("m2m 就绪失败: ${e.message}")
            setStep(5, M2mFullStepStatus.FAILED)
            return
        }
        setStep(5, M2mFullStepStatus.SUCCESS)

        // -- Step 6: Test access through proxy (verify full pipeline) --
        setStep(6, M2mFullStepStatus.RUNNING)
        try {
            withContext(Dispatchers.IO) {
                addLog("通过代理访问: $testUrl")
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", mixedPort))
                val response = HttpDownloader.openResponse(
                    testUrl,
                    HttpDownloader.Config(
                        connectTimeoutMs = 10_000L,
                        readTimeoutMs = 15_000L,
                        proxy = proxy,
                        tag = "M2M_FULL_TEST",
                    ),
                )
                try {
                    val code = response.code
                    val body = response.body?.string() ?: ""
                    val bodyPreview = body.take(200)
                    addLog("响应状态: $code, Content-Type: ${response.header("Content-Type") ?: "未知"}")
                    addLog("响应大小: ${body.length} 字符")
                    if (bodyPreview.isNotBlank()) addLog("内容预览: $bodyPreview")
                    if (code !in 200..399) error("非成功状态码: $code")
                    if (body.isBlank()) error("响应体为空")
                    addLog("代理访问测试通过")
                } finally {
                    response.close()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("代理访问测试失败: ${e.message}")
            setStep(6, M2mFullStepStatus.FAILED)
            return
        }
        setStep(6, M2mFullStepStatus.SUCCESS)

        // -- Step 7: Rebuild VPN (include self only) --
        setStep(7, M2mFullStepStatus.RUNNING)
        try {
            stopVpnService(context)
            vpnStarted = false
            addLog("已停止排除模式 m2m，正在重建（仅本应用模式）…")
            delay(1000)
            startVpnService(context, soPath, configFile.absolutePath, mixedPort, includeSelf = true)
            vpnStarted = true
            addLog("m2m 服务已启动（仅本应用模式）")
            val vpnReady2 = CompletableDeferred<Unit>()
            M2mFullTestService.callback = { event ->
                when (event) {
                    is M2mFullTestService.Event.Log -> addLog(event.message)
                    is M2mFullTestService.Event.Ready -> vpnReady2.complete(Unit)
                    is M2mFullTestService.Event.Error -> {
                        if (!vpnReady2.isCompleted) {
                            vpnReady2.completeExceptionally(Exception(event.message))
                        }
                    }
                }
            }
            withTimeout(30_000) { vpnReady2.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("m2m 重建失败: ${e.message}")
            setStep(7, M2mFullStepStatus.FAILED)
            return
        }
        setStep(7, M2mFullStepStatus.SUCCESS)

        // -- Step 8: Test direct access through VPN --
        setStep(8, M2mFullStepStatus.RUNNING)
        try {
            withContext(Dispatchers.IO) {
                addLog("通过 m2m 直接访问: $testUrl")
                val response = HttpDownloader.openResponse(
                    testUrl,
                    HttpDownloader.Config(
                        connectTimeoutMs = 10_000L,
                        readTimeoutMs = 15_000L,
                        tag = "M2M_FULL_TEST",
                    ),
                )
                try {
                    val code = response.code
                    val body = response.body?.string() ?: ""
                    val bodyPreview = body.take(200)
                    addLog("响应状态: $code, Content-Type: ${response.header("Content-Type") ?: "未知"}")
                    addLog("响应大小: ${body.length} 字符")
                    if (bodyPreview.isNotBlank()) addLog("内容预览: $bodyPreview")
                    if (code !in 200..399) error("非成功状态码: $code")
                    if (body.isBlank()) error("响应体为空")
                    addLog("m2m 直接访问测试通过")
                } finally {
                    response.close()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val reason = when {
                msg.contains("resolve host", ignoreCase = true) -> "DNS 解析失败（路由环路: m2m 出站 DNS 也经 VPN 回环）"
                msg.contains("timeout", ignoreCase = true) -> "连接超时（路由环路: m2m 出站经 VPN 回环）"
                msg.contains("Connection refused", ignoreCase = true) -> "连接被拒绝"
                else -> msg
            }
            addLog("m2m 直接访问失败: $reason")
            setStep(8, M2mFullStepStatus.FAILED)
            return
        }
        setStep(8, M2mFullStepStatus.SUCCESS)
    } finally {
        // -- Step 9: Cleanup (always runs) --
        if (vpnStarted) {
            setStep(9, M2mFullStepStatus.RUNNING)
            stopVpnService(context)
            addLog("m2m 已停止，资源已清理")
            setStep(9, M2mFullStepStatus.SUCCESS)
        } else {
            setStep(9, M2mFullStepStatus.SUCCESS)
        }
    }
}

private fun startVpnService(
    context: Context,
    pluginPath: String,
    configPath: String,
    mixedPort: Int,
    includeSelf: Boolean,
) {
    val intent = Intent(context, M2mFullTestService::class.java).apply {
        action = M2mFullTestService.ACTION_START
        putExtra(M2mFullTestService.EXTRA_PLUGIN_PATH, pluginPath)
        putExtra(M2mFullTestService.EXTRA_CONFIG_PATH, configPath)
        putExtra(M2mFullTestService.EXTRA_MIXED_PORT, mixedPort)
        putExtra(M2mFullTestService.EXTRA_INCLUDE_SELF, includeSelf)
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun stopVpnService(context: Context) {
    val intent = Intent(context, M2mFullTestService::class.java).apply {
        action = M2mFullTestService.ACTION_STOP
    }
    ContextCompat.startForegroundService(context, intent)
    M2mFullTestService.callback = null
}
