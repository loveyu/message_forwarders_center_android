@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.test.m2m

import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
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
            prefs.getString(
                M2mFullTestActivity.PREF_TEST_URL,
                M2mFullTestActivity.DEFAULT_TEST_URL
            ) ?: M2mFullTestActivity.DEFAULT_TEST_URL
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
            (activeStepIndex - 1).coerceAtLeast(0)
                .coerceAtMost(maxOf(0, steps.size - 3))
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
        steps.forEachIndexed { i, _ ->
            steps[i] = steps[i].copy(status = M2mFullStepStatus.IDLE)
        }
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
                    mixedPort = mixedPort.toIntOrNull()
                        ?: M2mFullTestActivity.DEFAULT_MIXED_PORT,
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
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
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
                Modifier.fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                                            Toast.makeText(
                                                context,
                                                "已复制",
                                                Toast.LENGTH_SHORT
                                            ).show()
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
