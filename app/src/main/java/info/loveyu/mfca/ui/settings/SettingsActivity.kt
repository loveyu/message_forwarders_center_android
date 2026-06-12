package info.loveyu.mfca.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.R
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.ui.LicenseActivity
import info.loveyu.mfca.ui.component.ComponentStatus
import info.loveyu.mfca.ui.theme.MfcaTheme
import info.loveyu.mfca.ui.theme.ThemeModeManager
import info.loveyu.mfca.util.AppStatusManager
import info.loveyu.mfca.util.config.ConfigBackupManager
import info.loveyu.mfca.util.cache.IconCacheManager
import info.loveyu.mfca.util.LogLevel
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.Preferences
import info.loveyu.mfca.util.exportAppDataToZip
import info.loveyu.mfca.util.http.HttpDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MfcaTheme {
                SettingsScreenContent(
                    onBack = { finish() },
                    onOpenLicenses = {
                        startActivity(Intent(this, LicenseActivity::class.java))
                    },
                    onOpenComponentDetail = { }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenComponentDetail: (ComponentStatus) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { Preferences(context) }

    var backupCount by remember { mutableIntStateOf(0) }
    var isExporting by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showExportSuccess by remember { mutableStateOf<String?>(null) }
    var autoStart by remember { mutableStateOf(false) }
    var showTabLabel by remember { mutableStateOf(preferences.showTabLabel) }
    var insecureConfigDownload by remember { mutableStateOf(preferences.insecureConfigDownload) }
    var themeMode by remember { mutableStateOf(ThemeModeManager.themeMode.value) }

    var iconCacheCount by remember { mutableIntStateOf(0) }
    var iconCacheSize by remember { mutableStateOf(0L) }
    var isClearingIconCache by remember { mutableStateOf(false) }
    var showClearIconCacheDialog by remember { mutableStateOf(false) }

    var selectedLogLevel by remember { mutableStateOf(LogManager.getLogLevel()) }
    var expandedLogLevel by remember { mutableStateOf(false) }
    var logToFile by remember { mutableStateOf(LogManager.isFileLoggingEnabled()) }
    var logToLogcatAll by remember { mutableStateOf(LogManager.isAllLogcatEnabled()) }
    var showClearLogsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        backupCount = ConfigBackupManager.listBackups(context).size
        val status = AppStatusManager.loadStatus(context)
        autoStart = status.autoStart
        val iconCacheManager = IconCacheManager.getInstance(context)
        val (count, size) = iconCacheManager.getCacheStats()
        iconCacheCount = count
        iconCacheSize = size
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { selectedUri ->
            scope.launch {
                isExporting = true
                try {
                    val result = withContext(Dispatchers.IO) {
                        exportAppDataToZip(context, selectedUri)
                    }
                    if (result != null) {
                        showExportSuccess = result
                        LogManager.logInfo("SETTINGS", "Export completed: $result")
                    } else {
                        Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    LogManager.logError("SETTINGS", "Export error: ${e.message}")
                } finally {
                    isExporting = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "备份管理",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                BackupSectionCard(
                    backupCount = backupCount,
                    isClearing = isClearing,
                    onClearBackups = { showClearConfirmDialog = true }
                )
            }

            item {
                Text(
                    text = "数据导出",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                ExportSectionCard(
                    isExporting = isExporting,
                    onExport = {
                        val timestamp =
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        exportLauncher.launch("mfca_export_$timestamp.zip")
                    },
                    onOpenDataDir = {
                        val dataDir = context.getExternalFilesDir(null)
                        if (dataDir != null) {
                            try {
                                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                }
                                context.startActivity(
                                    Intent.createChooser(intent, "浏览数据文件")
                                )
                                Toast.makeText(
                                    context,
                                    "数据目录: ${dataDir.absolutePath}",
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                LogManager.logError("UI", "打开数据目录失败: ${e.message}")
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("数据目录", dataDir.absolutePath)
                                )
                                Toast.makeText(
                                    context,
                                    "已复制路径: ${dataDir.absolutePath}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            Toast.makeText(context, "外部存储不可用", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            item {
                Text(
                    text = "系统设置",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                AutoStartCard(
                    autoStart = autoStart,
                    onAutoStartChange = { enabled ->
                        autoStart = enabled
                        val currentStatus = AppStatusManager.loadStatus(context)
                        AppStatusManager.saveStatus(context, currentStatus.copy(autoStart = enabled))
                        LogManager.logInfo(
                            "SETTINGS",
                            "Auto-start ${if (enabled) "enabled" else "disabled"}"
                        )
                    }
                )
            }
            item {
                ShowTabLabelCard(
                    showTabLabel = showTabLabel,
                    onTabLabelChange = { enabled ->
                        showTabLabel = enabled
                        preferences.showTabLabel = enabled
                    }
                )
            }
            item {
                InsecureSslCard(
                    insecureConfigDownload = insecureConfigDownload,
                    onInsecureChange = { enabled ->
                        insecureConfigDownload = enabled
                        preferences.insecureConfigDownload = enabled
                        HttpDownloader.defaultInsecure = enabled
                    }
                )
            }
            item {
                ThemeCard(
                    themeMode = themeMode,
                    onThemeModeChange = { value ->
                        themeMode = value
                        ThemeModeManager.setThemeMode(value, context)
                    }
                )
            }

            item {
                Text(
                    text = "日志设置",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                LogSettingsCard(
                    selectedLogLevel = selectedLogLevel,
                    expandedLogLevel = expandedLogLevel,
                    onLogLevelExpandedChange = { expandedLogLevel = it },
                    onLogLevelChange = { level ->
                        selectedLogLevel = level
                        LogManager.setLogLevel(level, preferences)
                        LogManager.appendLog(
                            LogLevel.INFO,
                            "SETTINGS",
                            "日志等级已切换为 ${level.name}"
                        )
                        expandedLogLevel = false
                    },
                    logToFile = logToFile,
                    onLogToFileChange = { enabled ->
                        logToFile = enabled
                        LogManager.setFileLoggingEnabled(enabled, preferences)
                    },
                    logToLogcatAll = logToLogcatAll,
                    onLogToLogcatAllChange = { enabled ->
                        logToLogcatAll = enabled
                        LogManager.setAllLogcatEnabled(enabled, preferences)
                    },
                    onClearLogs = { showClearLogsDialog = true }
                )
            }

            item {
                Text(
                    text = "缓存管理",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                IconCacheCard(
                    iconCacheCount = iconCacheCount,
                    iconCacheSize = iconCacheSize,
                    isClearingIconCache = isClearingIconCache,
                    onClearIconCache = { showClearIconCacheDialog = true }
                )
            }

            item {
                Text(
                    text = "关于",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                AboutSection(
                    onOpenLicenses = onOpenLicenses,
                    onOpenGitHub = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/loveyu/message_forwarders_center_android")
                        )
                        context.startActivity(intent)
                    }
                )
            }
        }
    }

    if (showClearConfirmDialog) {
        ClearBackupDialog(
            onConfirm = {
                showClearConfirmDialog = false
                isClearing = true
                scope.launch {
                    val success = withContext(Dispatchers.IO) {
                        ConfigBackupManager.clearAllBackups(context)
                    }
                    if (success) {
                        backupCount = 0
                        Toast.makeText(context, R.string.clear_success, Toast.LENGTH_SHORT).show()
                        LogManager.logInfo("SETTINGS", "All backups cleared")
                    } else {
                        Toast.makeText(context, R.string.clear_failed, Toast.LENGTH_SHORT).show()
                    }
                    isClearing = false
                }
            },
            onDismiss = { showClearConfirmDialog = false }
        )
    }

    if (showClearIconCacheDialog) {
        ClearIconCacheDialog(
            onConfirm = {
                showClearIconCacheDialog = false
                isClearingIconCache = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        IconCacheManager.getInstance(context).clearAll()
                        ForwardService.clearIconCaches()
                    }
                    iconCacheCount = 0
                    iconCacheSize = 0L
                    Toast.makeText(context, "图标缓存已清理", Toast.LENGTH_SHORT).show()
                    LogManager.logInfo("SETTINGS", "Icon cache cleared")
                    isClearingIconCache = false
                }
            },
            onDismiss = { showClearIconCacheDialog = false }
        )
    }

    if (showClearLogsDialog) {
        ClearLogsDialog(
            onConfirm = {
                showClearLogsDialog = false
                LogManager.clearAllLogs(context)
                Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
                LogManager.logInfo("SETTINGS", "All logs cleared")
            },
            onDismiss = { showClearLogsDialog = false }
        )
    }

    showExportSuccess?.let { path ->
        LaunchedEffect(path) {
            Toast.makeText(
                context,
                context.getString(R.string.export_success, path),
                Toast.LENGTH_LONG
            ).show()
            showExportSuccess = null
        }
    }
}
