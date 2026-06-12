package info.loveyu.mfca.config

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.R
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.ui.theme.MfcaTheme
import info.loveyu.mfca.util.config.ConfigBackupManager
import info.loveyu.mfca.util.config.ConfigDownloader
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.Preferences
import java.io.File

class ConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MfcaTheme {
                ConfigScreenContent(
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreenContent(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { Preferences(context) }

    var configUrl by remember { mutableStateOf(preferences.configFilePath) }
    var isLoading by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var backupList by remember { mutableStateOf(ConfigBackupManager.listBackups(context)) }

    fun openFileWithEditor(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/yaml")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "选择编辑器"))
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun openCurrentConfigInEditor() {
        val currentConfig = preferences.loadFullConfig()
        if (currentConfig.isNullOrBlank()) {
            Toast.makeText(context, R.string.config_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val tempFile = File(context.getExternalFilesDir(null) ?: context.cacheDir, "current_config.yaml")
            tempFile.writeText(currentConfig)
            openFileWithEditor(tempFile)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun restartService() {
        val stopIntent = Intent(context, ForwardService::class.java).apply {
            action = ForwardService.ACTION_STOP
        }
        context.startService(stopIntent)
        val startIntent = Intent(context, ForwardService::class.java).apply {
            action = ForwardService.ACTION_START
        }
        context.startService(startIntent)
    }

    fun handleDownloadConfig() {
        if (configUrl.isBlank()) {
            Toast.makeText(context, R.string.config_url_hint, Toast.LENGTH_SHORT).show()
            return
        }

        isLoading = true
        LogManager.logDebug("CONFIG", "开始下载配置: $configUrl")

        ConfigDownloader.downloadConfig(context, configUrl, insecure = preferences.insecureConfigDownload) { result ->
            isLoading = false
            result.fold(
                onSuccess = { content ->
                    LogManager.logDebug("CONFIG", "配置下载成功，开始解析...")

                    try {
                        val config = ConfigLoader.loadConfig(content)
                        LogManager.logDebug("CONFIG", "配置解析成功，版本: ${config.version}")

                        val currentConfig = preferences.loadFullConfig()
                        if (currentConfig != null) {
                            ConfigBackupManager.backupCurrentConfig(context, currentConfig)
                            LogManager.logDebug("CONFIG", "原配置已备份")
                        }

                        preferences.saveFullConfig(content)
                        preferences.configFilePath = configUrl

                        ForwardService.currentConfigUrl = configUrl
                        restartService()
                        LogManager.logInfo("CONFIG", "YAML配置应用成功")
                        Toast.makeText(context, R.string.config_download_success, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        LogManager.logError("CONFIG", "配置解析失败: ${e.message}")
                        Toast.makeText(context, R.string.config_validate_failed, Toast.LENGTH_SHORT).show()
                    }
                },
                onFailure = { error ->
                    LogManager.logError("CONFIG", "配置下载失败: ${error.message}")
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
                LogManager.logDebug("CONFIG", "配置解析成功")

                restartService()
                LogManager.logInfo("CONFIG", "配置重载成功")
                Toast.makeText(context, R.string.config_reload_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                LogManager.logError("CONFIG", "配置重载失败: ${e.message}")
                Toast.makeText(context, R.string.config_reload_failed, Toast.LENGTH_SHORT).show()
            }
        } else {
            LogManager.logWarn("CONFIG", "无保存的配置")
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

                restartService()
                LogManager.logInfo("CONFIG", "已恢复备份: ${backup.displayName}")
                Toast.makeText(context, R.string.config_restore_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                LogManager.logError("CONFIG", "备份恢复失败: ${e.message}")
                Toast.makeText(context, R.string.config_restore_failed, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, R.string.config_restore_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun handleDeleteBackup(backup: ConfigBackupManager.BackupInfo) {
        if (ConfigBackupManager.deleteBackup(backup)) {
            backupList = ConfigBackupManager.listBackups(context)
            LogManager.logInfo("CONFIG", "已删除备份: ${backup.displayName}")
        }
    }

    fun handleClearAllBackups() {
        ConfigBackupManager.clearAllBackups(context)
        backupList = ConfigBackupManager.listBackups(context)
        LogManager.logInfo("CONFIG", "已清空所有备份")
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

            ConfigActionButtons(
                isLoading = isLoading,
                onDownload = { handleDownloadConfig() },
                onReload = { handleReloadConfig() },
                onShowBackups = {
                    backupList = ConfigBackupManager.listBackups(context)
                    showBackupDialog = true
                },
            )

            ConfigOpenEditorButton(onOpen = { openCurrentConfigInEditor() })
        }
    }

    if (showBackupDialog) {
        ConfigBackupDialog(
            backupList = backupList,
            onDismiss = { showBackupDialog = false },
            onOpenFile = { backup -> openFileWithEditor(backup.file) },
            onRestore = { backup -> handleRestoreBackup(backup) },
            onDelete = { backup -> handleDeleteBackup(backup) },
            onClearAll = { handleClearAllBackups() },
        )
    }
}
