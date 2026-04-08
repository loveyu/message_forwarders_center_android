package info.loveyu.mfca

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.config.ConfigLoader
import info.loveyu.mfca.ui.HelpScreen
import info.loveyu.mfca.ui.SettingsScreen
import info.loveyu.mfca.util.ConfigBackupManager
import info.loveyu.mfca.util.ConfigDownloader
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.Preferences
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        ensureServiceRunning()

        setContent {
            MaterialTheme {
                var showHelp by remember { mutableStateOf(false) }
                var showSettings by remember { mutableStateOf(false) }

                when {
                    showSettings -> {
                        SettingsScreen(onBack = { showSettings = false })
                    }
                    showHelp -> {
                        HelpScreen(onBack = { showHelp = false })
                    }
                    else -> {
                        MainScreen(
                            onStartServer = { startServer() },
                            onStopServer = { stopServer() },
                            onShowHelp = { showHelp = true },
                            onShowSettings = { showSettings = true }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ensureServiceRunning()
    }

    private fun ensureServiceRunning() {
        if (!ForwardService.isServiceAlive()) {
            val intent = Intent(this, ForwardService::class.java).apply {
                action = ForwardService.ACTION_INIT
            }
            startForegroundService(intent)
        } else {
            ForwardService.refreshNotification()
        }
    }

    private fun startServer() {
        val intent = Intent(this, ForwardService::class.java).apply {
            action = ForwardService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopServer() {
        val intent = Intent(this, ForwardService::class.java).apply {
            action = ForwardService.ACTION_STOP
        }
        startService(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onShowHelp: () -> Unit,
    onShowSettings: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { Preferences(context) }
    val scope = rememberCoroutineScope()

    var isRunning by remember { mutableStateOf(ForwardService.isRunning) }
    var port by remember { mutableIntStateOf(preferences.port) }
    var forwardTarget by remember { mutableStateOf(preferences.forwardTarget) }
    var autoStart by remember { mutableStateOf(preferences.autoStart) }
    var receivedCount by remember { mutableIntStateOf(ForwardService.receivedCount) }
    var forwardedCount by remember { mutableIntStateOf(ForwardService.forwardedCount) }

    var configUrl by remember { mutableStateOf(preferences.configFilePath) }
    var isLoading by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(LogManager.isPaused()) }

    var showBackupDialog by remember { mutableStateOf(false) }
    var backupList by remember { mutableStateOf(ConfigBackupManager.listBackups(context)) }

    val logs by LogManager.logs.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    ForwardService.onStatsChanged = {
        receivedCount = ForwardService.receivedCount
        forwardedCount = ForwardService.forwardedCount
        isRunning = ForwardService.isRunning
    }

    DisposableEffect(Unit) {
        onDispose {
            ForwardService.onStatsChanged = null
        }
    }

    val localIp = remember {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return@remember addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        "0.0.0.0"
    }

    fun handleDownloadConfig() {
        if (configUrl.isBlank()) {
            Toast.makeText(context, R.string.config_url_hint, Toast.LENGTH_SHORT).show()
            return
        }

        isLoading = true
        LogManager.appendLog("CONFIG", "开始下载配置: $configUrl")

        ConfigDownloader.downloadConfig(configUrl) { result ->
            isLoading = false
            result.fold(
                onSuccess = { content ->
                    LogManager.appendLog("CONFIG", "配置下载成功，开始解析...")

                    try {
                        // Parse YAML config
                        val config = ConfigLoader.loadConfig(content)
                        LogManager.appendLog("CONFIG", "配置解析成功，版本: ${config.version}")

                        // Backup current config if exists
                        val currentConfig = preferences.loadFullConfig()
                        if (currentConfig != null) {
                            ConfigBackupManager.backupCurrentConfig(context, currentConfig)
                            LogManager.appendLog("CONFIG", "原配置已备份")
                        }

                        // Save config
                        preferences.saveFullConfig(content)
                        preferences.configFilePath = configUrl

                        // Try to start with new config via ForwardService
                        val success = ForwardService.loadConfig(content)
                        if (success) {
                            LogManager.appendLog("CONFIG", "YAML配置应用成功")
                            Toast.makeText(context, R.string.config_download_success, Toast.LENGTH_SHORT).show()
                        } else {
                            LogManager.appendLog("CONFIG", "配置加载失败")
                            Toast.makeText(context, R.string.config_validate_failed, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        LogManager.appendLog("CONFIG", "配置解析失败: ${e.message}")
                        Toast.makeText(context, R.string.config_validate_failed, Toast.LENGTH_SHORT).show()
                    }
                },
                onFailure = { error ->
                    LogManager.appendLog("CONFIG", "配置下载失败: ${error.message}")
                    Toast.makeText(context, R.string.config_download_failed, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    fun handleReloadConfig() {
        val savedConfig = preferences.loadFullConfig()
        if (savedConfig != null) {
            try {
                val config = ConfigLoader.loadConfig(savedConfig)
                LogManager.appendLog("CONFIG", "配置解析成功")

                val success = ForwardService.loadConfig(savedConfig)
                if (success) {
                    LogManager.appendLog("CONFIG", "配置重载成功")
                    Toast.makeText(context, R.string.config_reload_success, Toast.LENGTH_SHORT).show()
                } else {
                    LogManager.appendLog("CONFIG", "配置重载失败")
                    Toast.makeText(context, R.string.config_reload_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                LogManager.appendLog("CONFIG", "配置重载失败: ${e.message}")
                Toast.makeText(context, R.string.config_reload_failed, Toast.LENGTH_SHORT).show()
            }
        } else {
            LogManager.appendLog("CONFIG", "无保存的配置")
            Toast.makeText(context, R.string.config_reload_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun handleRestoreBackup(backup: ConfigBackupManager.BackupInfo) {
        val content = ConfigBackupManager.restoreBackup(backup)
        if (content != null) {
            // Backup current config
            val currentConfig = preferences.loadFullConfig()
            if (currentConfig != null) {
                ConfigBackupManager.backupCurrentConfig(context, currentConfig)
            }

            try {
                val config = ConfigLoader.loadConfig(content)
                preferences.saveFullConfig(content)

                val success = ForwardService.loadConfig(content)
                if (success) {
                    LogManager.appendLog("CONFIG", "已恢复备份: ${backup.displayName}")
                    Toast.makeText(context, R.string.config_restore_success, Toast.LENGTH_SHORT).show()
                } else {
                    LogManager.appendLog("CONFIG", "备份恢复失败")
                    Toast.makeText(context, R.string.config_restore_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                LogManager.appendLog("CONFIG", "备份恢复失败: ${e.message}")
                Toast.makeText(context, R.string.config_restore_failed, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, R.string.config_restore_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun handleDeleteBackup(backup: ConfigBackupManager.BackupInfo) {
        if (ConfigBackupManager.deleteBackup(backup)) {
            backupList = ConfigBackupManager.listBackups(context)
            LogManager.appendLog("CONFIG", "已删除备份: ${backup.displayName}")
        }
    }

    fun handleClearAllBackups() {
        ConfigBackupManager.clearAllBackups(context)
        backupList = ConfigBackupManager.listBackups(context)
        LogManager.appendLog("CONFIG", "已清空所有备份")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onShowSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置"
                        )
                    }
                    IconButton(onClick = onShowHelp) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "帮助"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 顶部状态卡片
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            Text(
                                text = if (isRunning) stringResource(R.string.status_running)
                                else stringResource(R.string.status_stopped),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isRunning) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isRunning) {
                            Button(onClick = onStopServer) {
                                Text(stringResource(R.string.stop_service))
                            }
                        } else {
                            Button(onClick = onStartServer) {
                                Text(stringResource(R.string.start_service))
                            }
                        }
                    }

                    if (isRunning) {
                        Text(
                            text = "http://$localIp:$port",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatItem(
                            label = stringResource(R.string.messages_received),
                            value = receivedCount
                        )
                        StatItem(
                            label = stringResource(R.string.messages_forwarded),
                            value = forwardedCount
                        )
                    }
                }
            }

            // 中部配置卡片
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.config_management),
                        style = MaterialTheme.typography.titleMedium
                    )

                    OutlinedTextField(
                        value = configUrl,
                        onValueChange = {
                            configUrl = it
                            preferences.configFilePath = it
                        },
                        label = { Text(stringResource(R.string.config_url)) },
                        placeholder = { Text(stringResource(R.string.config_url_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isLoading
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { handleDownloadConfig() },
                            modifier = Modifier.weight(1f),
                            enabled = !isLoading && !isRunning
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.download_config))
                            }
                        }

                        OutlinedButton(
                            onClick = { handleReloadConfig() },
                            modifier = Modifier.weight(1f),
                            enabled = !isLoading
                        ) {
                            Text(stringResource(R.string.reload_config))
                        }

                        OutlinedButton(
                            onClick = {
                                backupList = ConfigBackupManager.listBackups(context)
                                showBackupDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.restore_config))
                        }
                    }
                }
            }

            // 设置卡片
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleMedium
                    )

                    OutlinedTextField(
                        value = port.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { p ->
                                port = p
                                preferences.port = p
                            }
                        },
                        label = { Text(stringResource(R.string.port)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRunning,
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = forwardTarget,
                        onValueChange = {
                            forwardTarget = it
                            preferences.forwardTarget = it
                        },
                        label = { Text(stringResource(R.string.forward_target)) },
                        placeholder = { Text(stringResource(R.string.forward_target_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.settings))
                        Switch(
                            checked = autoStart,
                            onCheckedChange = {
                                autoStart = it
                                preferences.autoStart = it
                            }
                        )
                    }
                }
            }

            // 底部日志卡片
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.log_section),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    if (isPaused) {
                                        LogManager.resumeLogs()
                                    } else {
                                        LogManager.pauseLogs()
                                    }
                                    isPaused = LogManager.isPaused()
                                }
                            ) {
                                Text(
                                    if (isPaused) stringResource(R.string.resume_log)
                                    else stringResource(R.string.pause_log)
                                )
                            }
                            TextButton(
                                onClick = { LogManager.clearLogs() }
                            ) {
                                Text(stringResource(R.string.clear_log))
                            }
                            TextButton(
                                onClick = {
                                    val path = LogManager.saveLogsToFile(context)
                                    if (path != null) {
                                        Toast.makeText(context, R.string.log_saved, Toast.LENGTH_SHORT).show()
                                        LogManager.appendLog("APP", "日志已保存: $path")
                                    } else {
                                        Toast.makeText(context, R.string.log_save_failed, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.save_log))
                            }
                        }
                    }

                    HorizontalDivider()

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        state = listState
                    ) {
                        items(logs) { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // 备份恢复对话框
    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text(stringResource(R.string.backup_list_title)) },
            text = {
                if (backupList.isEmpty()) {
                    Text(stringResource(R.string.backup_empty))
                } else {
                    LazyColumn(
                        modifier = Modifier.height(300.dp)
                    ) {
                        items(backupList) { backup ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = backup.displayName,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { handleRestoreBackup(backup) }
                                ) {
                                    Text(stringResource(R.string.backup_restore))
                                }
                                TextButton(
                                    onClick = { handleDeleteBackup(backup) }
                                ) {
                                    Text(stringResource(R.string.backup_delete))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (backupList.isNotEmpty()) {
                    TextButton(
                        onClick = { handleClearAllBackups() }
                    ) {
                        Text(stringResource(R.string.backup_clear_all))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBackupDialog = false }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun StatItem(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
