package info.loveyu.mfca

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import info.loveyu.mfca.BuildConfig
import info.loveyu.mfca.ui.ComponentDetailSheet
import info.loveyu.mfca.ui.ComponentStatus
import info.loveyu.mfca.ui.ComponentStatusSheet
import info.loveyu.mfca.ui.ComponentType
import info.loveyu.mfca.ui.getEnabledAndDisabledComponents
import info.loveyu.mfca.util.AppStatusManager
import info.loveyu.mfca.util.ConfigBackupManager
import info.loveyu.mfca.util.LogLevel
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                SettingsScreenContent(
                    onBack = { finish() },
                    onOpenLicenses = {
                        startActivity(Intent(this, LicenseActivity::class.java))
                    },
                    onOpenComponentDetail = { component ->
                        // Handle component detail - this activity will show the bottom sheet
                    }
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

    // Load backup count and status
    LaunchedEffect(Unit) {
        backupCount = ConfigBackupManager.listBackups(context).size
        val status = AppStatusManager.loadStatus(context)
        autoStart = status.autoStart
    }

    // File export launcher using Storage Access Framework
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
                        LogManager.appendLog("SETTINGS", "Export completed: $result")
                    } else {
                        Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    LogManager.appendLog("SETTINGS", "Export error: ${e.message}")
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
            // 备份管理
            item {
                Text(
                    text = "备份管理",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("配置备份版本总数")
                            Text(
                                text = backupCount.toString(),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "清空所有备份",
                                color = MaterialTheme.colorScheme.error
                            )
                            Button(
                                onClick = { showClearConfirmDialog = true },
                                enabled = backupCount > 0 && !isClearing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                if (isClearing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("清空")
                            }
                        }
                    }
                }
            }

            // 数据导出
            item {
                Text(
                    text = "数据导出",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "导出应用私有目录",
                            style = MaterialTheme.typography.bodyLarge
                        )

                        Text(
                            text = "将应用的私有数据目录（包括配置、队列数据、日志等）打包为 ZIP 文件导出到您选择的位置。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = {
                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                val fileName = "mfca_export_$timestamp.zip"
                                exportLauncher.launch(fileName)
                            },
                            enabled = !isExporting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("导出中...")
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("导出 ZIP")
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val dataDir = context.getExternalFilesDir(null)
                                if (dataDir != null) {
                                    try {
                                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                            type = "*/*"
                                        }
                                        context.startActivity(Intent.createChooser(intent, "浏览数据文件"))
                                        Toast.makeText(context, "数据目录: ${dataDir.absolutePath}", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        LogManager.appendLog("UI", "打开数据目录失败: ${e.message}")
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("数据目录", dataDir.absolutePath))
                                        Toast.makeText(context, "已复制路径: ${dataDir.absolutePath}", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    Toast.makeText(context, "外部存储不可用", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("打开数据目录")
                        }
                    }
                }
            }

            // 系统设置
            item {
                Text(
                    text = "系统设置",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("开机自启动")
                        Switch(
                            checked = autoStart,
                            onCheckedChange = { enabled ->
                                autoStart = enabled
                                val currentStatus = AppStatusManager.loadStatus(context)
                                val newStatus = currentStatus.copy(autoStart = enabled)
                                AppStatusManager.saveStatus(context, newStatus)
                                LogManager.appendLog("SETTINGS", "Auto-start ${if (enabled) "enabled" else "disabled"}")
                            }
                        )
                    }
                }
            }

            // 日志设置
            item {
                Text(
                    text = "日志设置",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 日志等级
                        var selectedLogLevel by remember { mutableStateOf(LogManager.getLogLevel()) }
                        var expandedLogLevel by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("日志等级")
                        }
                        ExposedDropdownMenuBox(
                            expanded = expandedLogLevel,
                            onExpandedChange = { expandedLogLevel = it }
                        ) {
                            OutlinedTextField(
                                value = selectedLogLevel.name,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedLogLevel) },
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expandedLogLevel,
                                onDismissRequest = { expandedLogLevel = false }
                            ) {
                                LogLevel.entries.forEach { level ->
                                    DropdownMenuItem(
                                        text = { Text(level.name) },
                                        onClick = {
                                            selectedLogLevel = level
                                            LogManager.setLogLevel(level, preferences)
                                            expandedLogLevel = false
                                        }
                                    )
                                }
                            }
                        }

                        HorizontalDivider()

                        // 日志输出到文件
                        var logToFile by remember { mutableStateOf(LogManager.isFileLoggingEnabled()) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("日志保存到文件")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "启用后日志将持续写入文件",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Switch(
                                checked = logToFile,
                                onCheckedChange = { enabled ->
                                    logToFile = enabled
                                    LogManager.setFileLoggingEnabled(enabled, preferences)
                                }
                            )
                        }

                        HorizontalDivider()

                        // 所有日志记录到logcat
                        var logToLogcatAll by remember { mutableStateOf(LogManager.isAllLogcatEnabled()) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("所有日志记录到Logcat")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "默认WARN及以上记录到Logcat",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Switch(
                                checked = logToLogcatAll,
                                onCheckedChange = { enabled ->
                                    logToLogcatAll = enabled
                                    LogManager.setAllLogcatEnabled(enabled, preferences)
                                }
                            )
                        }
                    }
                }
            }

            // 关于
            item {
                Text(
                    text = "关于",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("应用名称")
                            Text("消息转发中心")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("版本")
                            if (BuildConfig.DEBUG) {
                                Text(
                                    text = "DEBUG",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Text(BuildConfig.VERSION_NAME)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("构建时间")
                            Text(BuildConfig.BUILD_TIME)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("分支")
                            Text(BuildConfig.GIT_BRANCH)
                        }
                        HorizontalDivider()
                        TextButton(
                            onClick = onOpenLicenses,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("开源许可")
                        }
                        TextButton(
                            onClick = {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://github.com/loveyu/message_forwarders_center_android"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.github))
                        }
                    }
                }
            }
        }
    }

    // Clear confirmation dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("确认清空") },
            text = { Text("确定要清空所有配置备份吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmDialog = false
                        isClearing = true
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                ConfigBackupManager.clearAllBackups(context)
                            }
                            if (success) {
                                backupCount = 0
                                Toast.makeText(context, R.string.clear_success, Toast.LENGTH_SHORT).show()
                                LogManager.appendLog("SETTINGS", "All backups cleared")
                            } else {
                                Toast.makeText(context, R.string.clear_failed, Toast.LENGTH_SHORT).show()
                            }
                            isClearing = false
                        }
                    }
                ) {
                    Text("确认清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Export success snackbar
    showExportSuccess?.let { path ->
        LaunchedEffect(path) {
            Toast.makeText(context, context.getString(R.string.export_success, path), Toast.LENGTH_LONG).show()
            showExportSuccess = null
        }
    }
}

/**
 * 导出应用私有数据到 ZIP 文件
 */
private suspend fun exportAppDataToZip(context: Context, outputUri: Uri): String? {
    return withContext(Dispatchers.IO) {
        try {
            val filesDir = context.getExternalFilesDir(null) ?: context.filesDir
            val outputStream = context.contentResolver.openOutputStream(outputUri) ?: return@withContext null

            ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                zipDirectory(filesDir, filesDir.name, zipOut)
            }

            // 获取导出的文件名
            val cursor = context.contentResolver.query(outputUri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayNameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex >= 0) {
                        return@withContext it.getString(displayNameIndex)
                    }
                }
            }
            return@withContext outputUri.lastPathSegment
        } catch (e: Exception) {
            LogManager.appendLog("SETTINGS", "Export error: ${e.message}")
            null
        }
    }
}

/**
 * 递归压缩目录
 */
private fun zipDirectory(
    sourceDir: File,
    entryName: String,
    zipOut: ZipOutputStream,
    bufferSize: Int = 8192
) {
    val files = sourceDir.listFiles() ?: return

    for (file in files) {
        val entryPath = if (entryName.isEmpty()) file.name else "$entryName/${file.name}"

        when {
            file.isDirectory -> {
                zipDirectory(file, entryPath, zipOut, bufferSize)
            }
            file.isFile -> {
                try {
                    BufferedInputStream(FileInputStream(file), bufferSize).use { input ->
                        val entry = ZipEntry(entryPath)
                        entry.time = file.lastModified()
                        zipOut.putNextEntry(entry)

                        val buffer = ByteArray(bufferSize)
                        var len: Int
                        while (input.read(buffer).also { len = it } != -1) {
                            zipOut.write(buffer, 0, len)
                        }
                        zipOut.closeEntry()
                    }
                } catch (e: Exception) {
                    LogManager.appendLog("SETTINGS", "Failed to zip file ${file.name}: ${e.message}")
                }
            }
        }
    }
}
