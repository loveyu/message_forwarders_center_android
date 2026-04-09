package info.loveyu.mfca.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.R
import info.loveyu.mfca.config.ConfigLoader
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.ConfigBackupManager
import info.loveyu.mfca.util.ConfigDownloader
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.Preferences
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { Preferences(context) }

    var configUrl by remember { mutableStateOf(preferences.configFilePath) }
    var isLoading by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var backupList by remember { mutableStateOf(ConfigBackupManager.listBackups(context)) }

    // Open file with external editor
    fun openFileWithEditor(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/json")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "选择编辑器"))
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Open current config in external editor
    fun openCurrentConfigInEditor() {
        val currentConfig = preferences.loadFullConfig()
        if (currentConfig.isNullOrBlank()) {
            Toast.makeText(context, R.string.config_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val tempFile = File(context.cacheDir, "current_config.json")
            tempFile.writeText(currentConfig)
            openFileWithEditor(tempFile)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
                        val config = ConfigLoader.loadConfig(content)
                        LogManager.appendLog("CONFIG", "配置解析成功，版本: ${config.version}")

                        val currentConfig = preferences.loadFullConfig()
                        if (currentConfig != null) {
                            ConfigBackupManager.backupCurrentConfig(context, currentConfig)
                            LogManager.appendLog("CONFIG", "原配置已备份")
                        }

                        preferences.saveFullConfig(content)
                        preferences.configFilePath = configUrl

                        val success = ForwardService.loadConfig(content, configUrl)
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
                ConfigLoader.loadConfig(savedConfig)
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
            val currentConfig = preferences.loadFullConfig()
            if (currentConfig != null) {
                ConfigBackupManager.backupCurrentConfig(context, currentConfig)
            }

            try {
                ConfigLoader.loadConfig(content)
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
                title = { Text(stringResource(R.string.config_management)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                    enabled = !isLoading && !ForwardService.isRunning
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

            // Open current config in external editor
            OutlinedButton(
                onClick = { openCurrentConfigInEditor() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("用外部编辑器打开当前配置")
            }
        }
    }

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
                                    onClick = { openFileWithEditor(backup.file) }
                                ) {
                                    Text("打开")
                                }
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
