@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.test

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.material3.SheetValue
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
import info.loveyu.mfca.R
import info.loveyu.mfca.plugin.M2mPluginCore
import info.loveyu.mfca.plugin.PluginManager
import info.loveyu.mfca.ui.theme.MfcaTheme
import info.loveyu.mfca.util.HttpDownloader
import info.loveyu.mfca.util.StoragePathResolver
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class M2mTestActivity : ComponentActivity() {

    companion object {
        const val PREFS_NAME = "m2m_test_prefs"
        const val PREF_CORE_URL = "core_url"
        const val PREF_PROXY_URL = "proxy_url"
        const val PREF_CONFIG_URL = "config_url"
        const val PREF_MIXED_PORT = "mixed_port"
        const val PREF_TEST_URL = "test_url"
        const val PREF_INSECURE = "insecure"
        const val DEFAULT_MIXED_PORT = 2080
        const val DEFAULT_TEST_URL = "https://www.google.com"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MfcaTheme { M2mTestScreen(onBack = { finish() }) } }
    }
}

// -- Step model --

private enum class M2mStepStatus { IDLE, RUNNING, SUCCESS, FAILED }

private data class M2mTestStep(val label: String, var status: M2mStepStatus = M2mStepStatus.IDLE)

// -- Settings bottom sheet --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheetContent(
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
    insecure: Boolean,
    onInsecureChange: (Boolean) -> Unit,
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
            onTestUrlChange,
            label = { Text("测试访问地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("跳过 SSL 证书校验", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = insecure, onCheckedChange = onInsecureChange)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onConfirm) { Text("确定") }
        }
    }
}

// -- UI --

@Composable
private fun M2mTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val prefs = remember {
        context.getSharedPreferences(M2mTestActivity.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    var coreUrl by remember {
        mutableStateOf(prefs.getString(M2mTestActivity.PREF_CORE_URL, "") ?: "")
    }
    var proxyUrl by remember {
        mutableStateOf(prefs.getString(M2mTestActivity.PREF_PROXY_URL, "") ?: "")
    }
    var configUrl by remember {
        mutableStateOf(prefs.getString(M2mTestActivity.PREF_CONFIG_URL, "") ?: "")
    }
    var mixedPort by remember {
        mutableStateOf(
            prefs.getString(
                M2mTestActivity.PREF_MIXED_PORT,
                M2mTestActivity.DEFAULT_MIXED_PORT.toString(),
            ) ?: M2mTestActivity.DEFAULT_MIXED_PORT.toString()
        )
    }
    var testUrl by remember {
        mutableStateOf(
            prefs.getString(M2mTestActivity.PREF_TEST_URL, M2mTestActivity.DEFAULT_TEST_URL)
                ?: M2mTestActivity.DEFAULT_TEST_URL
        )
    }
    var insecure by remember {
        mutableStateOf(prefs.getBoolean(M2mTestActivity.PREF_INSECURE, true))
    }
    var showSettings by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var overallProgress by remember { mutableFloatStateOf(0f) }

    val logs = remember { mutableStateListOf<String>() }
    val steps = remember {
        mutableStateListOf(
            M2mTestStep("下载 / 验证核心插件"),
            M2mTestStep("下载配置文件"),
            M2mTestStep("修改混合端口"),
            M2mTestStep("启动代理服务"),
            M2mTestStep("等待代理就绪"),
            M2mTestStep("测试访问"),
            M2mTestStep("清理资源"),
        )
    }
    val logListState = rememberLazyListState()
    val stepListState = rememberLazyListState()

    val activeStepIndex by remember {
        derivedStateOf {
            val running = steps.indexOfFirst { it.status == M2mStepStatus.RUNNING }
            if (running >= 0) return@derivedStateOf running
            val failed = steps.indexOfFirst { it.status == M2mStepStatus.FAILED }
            if (failed >= 0) return@derivedStateOf failed
            val lastSuccess = steps.indexOfLast { it.status == M2mStepStatus.SUCCESS }
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

    fun setStep(idx: Int, status: M2mStepStatus) {
        steps[idx] = steps[idx].copy(status = status)
        val done = steps.count { it.status == M2mStepStatus.SUCCESS }
        overallProgress = done.toFloat() / steps.size
    }

    fun resetAll() {
        steps.forEachIndexed { i, _ -> steps[i] = steps[i].copy(status = M2mStepStatus.IDLE) }
        logs.clear()
        overallProgress = 0f
    }

    fun saveSettings() {
        prefs.edit()
            .putString(M2mTestActivity.PREF_CORE_URL, coreUrl)
            .putString(M2mTestActivity.PREF_PROXY_URL, proxyUrl)
            .putString(M2mTestActivity.PREF_CONFIG_URL, configUrl)
            .putString(M2mTestActivity.PREF_MIXED_PORT, mixedPort)
            .putString(M2mTestActivity.PREF_TEST_URL, testUrl)
            .putBoolean(M2mTestActivity.PREF_INSECURE, insecure)
            .apply()
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
                runM2mTest(
                    context = context,
                    coreUrl = coreUrl,
                    proxyUrl = proxyUrl.ifBlank { null },
                    configUrl = configUrl,
                    mixedPort = mixedPort.toIntOrNull() ?: M2mTestActivity.DEFAULT_MIXED_PORT,
                    testUrl = testUrl.ifBlank { M2mTestActivity.DEFAULT_TEST_URL },
                    insecure = insecure,
                    addLog = { addLog(it) },
                    setStep = { i, s -> setStep(i, s) },
                )
            } catch (_: CancellationException) {
                addLog("测试已取消")
            } finally {
                steps.forEachIndexed { i, step ->
                    if (step.status == M2mStepStatus.RUNNING) {
                        steps[i] = step.copy(status = M2mStepStatus.IDLE)
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
                title = { Text(stringResource(R.string.test_m2m_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        sheetContent = {
            if (showSettings) {
                SettingsSheetContent(
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
                    insecure = insecure,
                    onInsecureChange = { insecure = it },
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
            // Action row: test button + settings button
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

            // Overall progress
            LinearProgressIndicator(
                progress = { overallProgress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )

            // Step list
            LazyColumn(
                state = stepListState,
                modifier = Modifier.fillMaxWidth().height(120.dp),
                userScrollEnabled = false,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(steps) { step -> M2mStepRow(step) }
            }

            // Log output
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
private fun M2mStepRow(step: M2mTestStep) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (step.status) {
            M2mStepStatus.IDLE ->
                Box(
                    Modifier.size(12.dp)
                        .background(Color.Gray.copy(alpha = 0.4f), CircleShape)
                )
            M2mStepStatus.RUNNING ->
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            M2mStepStatus.SUCCESS ->
                Box(Modifier.size(12.dp).background(Color(0xFF4CAF50), CircleShape))
            M2mStepStatus.FAILED ->
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
                    M2mStepStatus.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
        )
    }
}

// -- Test logic --

private suspend fun runM2mTest(
    context: Context,
    coreUrl: String,
    proxyUrl: String?,
    configUrl: String,
    mixedPort: Int,
    testUrl: String,
    insecure: Boolean,
    addLog: (String) -> Unit,
    setStep: (Int, M2mStepStatus) -> Unit,
) {
    val workDir = File(context.filesDir, "m2m_test").apply { mkdirs() }
    var core: M2mPluginCore? = null

    try {
        // -- Step 0: Download / verify plugin --
        setStep(0, M2mStepStatus.RUNNING)
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
                setStep(0, M2mStepStatus.FAILED)
                return
            }
        addLog("插件路径: $soPath")
        setStep(0, M2mStepStatus.SUCCESS)

        // -- Step 1: Download config file --
        setStep(1, M2mStepStatus.RUNNING)
        val configFile =
            try {
                withContext(Dispatchers.IO) {
                    downloadConfigFile(context, configUrl, proxyUrl, workDir, addLog, insecure)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("配置文件获取失败: ${e.message}")
                setStep(1, M2mStepStatus.FAILED)
                return
            }
        addLog("配置文件: ${configFile.absolutePath}")
        setStep(1, M2mStepStatus.SUCCESS)

        // -- Step 2: Override mixed port --
        setStep(2, M2mStepStatus.RUNNING)
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
            setStep(2, M2mStepStatus.FAILED)
            return
        }
        setStep(2, M2mStepStatus.SUCCESS)

        // -- Step 3: Start m2m proxy --
        setStep(3, M2mStepStatus.RUNNING)
        val logFile = File(workDir, "m2m.log")
        try {
            withContext(Dispatchers.IO) {
                logFile.writeText("")
                core = M2mPluginCore().also {
                    it.load(soPath)
                    addLog("核心版本: ${it.version() ?: "未知"}")
                    val args = listOf("-d", workDir.absolutePath, "-f", configFile.absolutePath)
                    addLog("启动参数: ${args.joinToString(" ")}")
                    val ret = it.start(args, logFile.absolutePath)
                    if (ret != 0) {
                        val log = if (logFile.exists()) logFile.readText() else ""
                        error("启动失败 (code $ret): ${log.take(500).ifBlank { "无日志输出" }}")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("启动代理失败: ${e.message}")
            dumpLogFile(logFile, addLog)
            setStep(3, M2mStepStatus.FAILED)
            return
        }
        setStep(3, M2mStepStatus.SUCCESS)

        // -- Step 4: Wait for proxy ready --
        setStep(4, M2mStepStatus.RUNNING)
        try {
            withContext(Dispatchers.IO) {
                addLog("等待代理端口 $mixedPort 就绪…")
                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline) {
                    val c = core
                    if (c != null && !c.isRunning()) {
                        val log = if (logFile.exists()) logFile.readText() else ""
                        error("代理进程意外退出: ${log.takeLast(500).ifBlank { "无日志输出" }}")
                    }
                    if (canConnect(mixedPort)) {
                        addLog("代理端口 $mixedPort 已就绪")
                        return@withContext
                    }
                    Thread.sleep(300)
                }
                error("等待代理就绪超时 (15s)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("代理就绪检查失败: ${e.message}")
            dumpLogFile(logFile, addLog)
            setStep(4, M2mStepStatus.FAILED)
            return
        }
        setStep(4, M2mStepStatus.SUCCESS)

        // -- Step 5: Test access --
        setStep(5, M2mStepStatus.RUNNING)
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
                        tag = "M2M_TEST",
                    ),
                )
                try {
                    val code = response.code
                    val body = response.body?.string() ?: ""
                    val bodyPreview = body.take(200)
                    addLog("响应状态: $code, Content-Type: ${response.header("Content-Type") ?: "未知"}")
                    addLog("响应大小: ${body.length} 字符")
                    if (bodyPreview.isNotBlank()) {
                        addLog("内容预览: $bodyPreview")
                    }
                    if (code !in 200..399) {
                        error("非成功状态码: $code")
                    }
                    if (body.isBlank()) {
                        error("响应体为空")
                    }
                    val isHtml = body.trimStart().startsWith("<", ignoreCase = true)
                    val looksLikeContent = body.length > 100
                    if (!isHtml && !looksLikeContent) {
                        error("响应内容不像有效页面 (前缀: ${body.take(50)})")
                    }
                    addLog("访问测试通过")
                } finally {
                    response.close()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("访问测试失败: ${e.message}")
            setStep(5, M2mStepStatus.FAILED)
            return
        }
        setStep(5, M2mStepStatus.SUCCESS)

        // -- Step 6: Cleanup --
        setStep(6, M2mStepStatus.RUNNING)
        try {
            withContext(Dispatchers.IO) {
                core?.let { c ->
                    c.stop()
                    val deadline = System.currentTimeMillis() + 2_000
                    while (c.isRunning() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(100)
                    }
                    addLog("代理已停止")
                }
            }
        } catch (e: Exception) {
            addLog("停止代理异常: ${e.message}")
        }
        setStep(6, M2mStepStatus.SUCCESS)
    } finally {
        try {
            core?.stop()
        } catch (_: Exception) {}
    }
}

internal fun overrideMixedPort(content: String, port: Int): String {
    val regex = Regex("^(mixed-port:\\s*)\\d+", RegexOption.MULTILINE)
    return if (regex.containsMatchIn(content)) {
        regex.replace(content) { "${it.groupValues[1]}$port" }
    } else {
        "mixed-port: $port\n$content"
    }
}

internal suspend fun downloadConfigFile(
    context: Context,
    url: String,
    proxyAddress: String?,
    workDir: File,
    addLog: (String) -> Unit,
    insecure: Boolean = true,
): File = withContext(Dispatchers.IO) {
    val destFile = File(workDir, "config.yaml")
    val isLocal =
        url.startsWith("data://") || url.startsWith("sdcard://") ||
            url.startsWith("cache://") || url.startsWith("file://")

    if (isLocal) {
        addLog("从本地路径获取配置: $url")
        val src = StoragePathResolver.resolveFile(context, url, allowRawPath = true)
        src.copyTo(destFile, overwrite = true)
    } else {
        addLog("正在下载配置文件: $url")
        val proxy = proxyAddress?.trim()?.takeIf { it.isNotBlank() }?.let {
            HttpDownloader.parseProxy(it)
        }
        HttpDownloader.downloadToFileSuspend(
            url,
            destFile,
            HttpDownloader.Config(
                connectTimeoutMs = 30_000L,
                readTimeoutMs = 30_000L,
                proxy = proxy,
                sslRetryCount = 2,
                tag = "M2M_TEST",
                insecure = insecure,
            ),
        )
        addLog("配置文件下载完成 (${destFile.length()} bytes)")
    }
    destFile
}

internal fun canConnect(port: Int): Boolean {
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 300)
        }
        true
    } catch (_: Exception) {
        false
    }
}

internal fun dumpLogFile(logFile: File, addLog: (String) -> Unit) {
    if (!logFile.exists()) return
    try {
        val content = logFile.readText()
        if (content.isBlank()) {
            addLog("日志文件为空")
        } else {
            addLog("--- 日志转储 ---")
            content.lines().take(50).forEach { line ->
                if (line.isNotBlank()) addLog(line)
            }
            addLog("--- 日志结束 ---")
        }
    } catch (e: Exception) {
        addLog("读取日志失败: ${e.message}")
    }
}
