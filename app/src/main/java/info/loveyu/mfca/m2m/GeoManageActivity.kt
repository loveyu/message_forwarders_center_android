@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.m2m

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.R
import info.loveyu.mfca.config.ConfigLoader
import info.loveyu.mfca.config.GeoConfig
import info.loveyu.mfca.ui.theme.MfcaTheme

import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GeoManageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MfcaTheme {
                GeoManageScreen()
            }
        }
    }

}

private const val TAG = "GeoManage"

@Composable
private fun GeoManageScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val geoConfig = remember { loadGeoConfig(context) }
    val stateStore = remember { M2mStateStore(context.applicationContext) }

    val cacheStates = remember { mutableStateListOf<GeoCacheState>() }
    val workingTypes = remember { mutableStateListOf<GeoFileType>() }
    val downloadProgress = remember { mutableStateMapOf<String, Float>() }
    val downloadError = remember { mutableStateMapOf<String, String?>() }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var editingType by remember { mutableStateOf<GeoFileType?>(null) }
    var editingUrl by remember { mutableStateOf("") }
    var confirmDeleteType by remember { mutableStateOf<GeoFileType?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmResetType by remember { mutableStateOf<GeoFileType?>(null) }
    var confirmResetAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        refreshStates(context, cacheStates)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.geo_manage_title)) },
                navigationIcon = {
                    IconButton(onClick = { (context as? Activity)?.finish() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.geo_batch_download)) },
                                onClick = {
                                    showOverflowMenu = false
                                    scope.launch {
                                        batchDownload(context, geoConfig, stateStore, cacheStates, workingTypes, downloadProgress, downloadError, snackbarHostState)
                                    }
                                },
                                enabled = workingTypes.isEmpty(),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.geo_batch_delete)) },
                                onClick = {
                                    showOverflowMenu = false
                                    if (cacheStates.any { it.isCached }) {
                                        confirmDeleteAll = true
                                    } else {
                                        scope.launch { snackbarHostState.showSnackbar("没有已缓存的数据") }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.geo_reset_all)) },
                                onClick = {
                                    showOverflowMenu = false
                                    confirmResetAll = true
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            items(GeoFileType.all, key = { it.key }) { type ->
                val state = cacheStates.firstOrNull { it.type == type } ?: GeoCacheState(type, false)
                val effectiveUrl = GeoFileManager.getEffectiveUrl(
                    type,
                    GeoFileManager.resolveUrl(type, geoConfig),
                    stateStore,
                )
                val isWorking = type in workingTypes
                GeoFileCard(
                    type = type,
                    cacheState = state,
                    effectiveUrl = effectiveUrl,
                    hasDefault = GeoFileManager.resolveUrl(type, geoConfig).isNotBlank(),
                    isWorking = isWorking,
                    progress = downloadProgress[type.key] ?: 0f,
                    errorText = downloadError[type.key],
                    onDownload = {
                        scope.launch {
                            downloadSingle(context, type, effectiveUrl, stateStore, cacheStates, workingTypes, downloadProgress, downloadError, snackbarHostState)
                        }
                    },
                    onDelete = {
                        confirmDeleteType = type
                    },
                    onEditUrl = {
                        editingType = type
                        editingUrl = effectiveUrl
                    },
                    onResetUrl = {
                        confirmResetType = type
                    },
                )
            }
        }
    }

    // ── Edit URL Dialog ──
    editingType?.let { type ->
        AlertDialog(
            onDismissRequest = { editingType = null },
            title = { Text(stringResource(R.string.geo_url_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = editingUrl,
                    onValueChange = { editingUrl = it },
                    label = { Text(stringResource(R.string.geo_url_dialog_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    stateStore.setGeoUrlOverride(type.key, editingUrl.takeIf { it.isNotBlank() })
                    editingType = null
                    scope.launch { refreshStates(context, cacheStates) }
                }) {
                    Text(stringResource(R.string.geo_url_dialog_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingType = null }) {
                    Text(stringResource(R.string.geo_url_dialog_cancel))
                }
            },
        )
    }

    // ── Confirm Delete Single ──
    confirmDeleteType?.let { type ->
        AlertDialog(
            onDismissRequest = { confirmDeleteType = null },
            title = { Text(stringResource(R.string.geo_delete_confirm, type.label)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteType = null
                    scope.launch {
                        deleteSingle(context, type, cacheStates, snackbarHostState)
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteType = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Confirm Delete All ──
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.geo_delete_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    scope.launch {
                        deleteAll(context, cacheStates, snackbarHostState)
                    }
                }) { Text("全部删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Confirm Reset Single ──
    confirmResetType?.let { type ->
        AlertDialog(
            onDismissRequest = { confirmResetType = null },
            title = { Text(stringResource(R.string.geo_reset_confirm, type.label)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmResetType = null
                    stateStore.setGeoUrlOverride(type.key, null)
                    scope.launch { refreshStates(context, cacheStates) }
                }) { Text("重置") }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetType = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // ── Confirm Reset All ──
    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text(stringResource(R.string.geo_reset_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmResetAll = false
                    GeoFileType.all.forEach { stateStore.setGeoUrlOverride(it.key, null) }
                    scope.launch { refreshStates(context, cacheStates) }
                }) { Text("全部重置") }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetAll = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun GeoFileCard(
    type: GeoFileType,
    cacheState: GeoCacheState,
    effectiveUrl: String,
    hasDefault: Boolean,
    isWorking: Boolean,
    progress: Float,
    errorText: String?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onEditUrl: () -> Unit,
    onResetUrl: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        when (type) {
                            GeoFileType.GEOIP -> R.string.geo_type_geoip
                            GeoFileType.GEOSITE -> R.string.geo_type_geosite
                            GeoFileType.COUNTRY -> R.string.geo_type_country
                            GeoFileType.ASN -> R.string.geo_type_asn
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_options),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                if (isWorking) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.geo_downloading))
                                    }
                                } else {
                                    Text(
                                        stringResource(
                                            if (cacheState.isCached) R.string.geo_redownload else R.string.geo_download,
                                        ),
                                    )
                                }
                            },
                            onClick = {
                                showMenu = false
                                if (!isWorking) onDownload()
                            },
                            enabled = !isWorking && effectiveUrl.isNotBlank(),
                        )
                        if (cacheState.isCached) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.geo_delete_cache)) },
                                onClick = { showMenu = false; onDelete() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.geo_edit_url)) },
                            onClick = { showMenu = false; onEditUrl() },
                        )
                        if (hasDefault) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.geo_reset_url)) },
                                onClick = { showMenu = false; onResetUrl() },
                            )
                        }
                    }
                }
            }

            // Status line
            if (isWorking && progress > 0f) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else if (isWorking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    text = if (cacheState.isCached) {
                        val sizeText = formatFileSize(cacheState.fileSize)
                        stringResource(R.string.geo_status_cached, sizeText)
                    } else {
                        stringResource(R.string.geo_status_not_cached)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cacheState.isCached) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // URL line
            Text(
                text = if (effectiveUrl.isNotBlank()) effectiveUrl else stringResource(R.string.geo_no_default_url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun loadGeoConfig(context: android.content.Context): GeoConfig {
    val appContext = context.applicationContext
    val dir = File(appContext.filesDir, "config")
    val configFile = File(dir, "current.yaml")
    return if (configFile.exists()) {
        try {
            ConfigLoader.loadConfig(configFile.readText()).geo
        } catch (e: Exception) {
            LogManager.logError(TAG, "Failed to load geo config: ${e.message}")
            GeoConfig()
        }
    } else {
        GeoConfig()
    }
}

private suspend fun refreshStates(
    context: android.content.Context,
    cacheStates: MutableList<GeoCacheState>,
) = withContext(Dispatchers.IO) {
    val states = GeoFileManager.getAllCacheStates(context)
    cacheStates.clear()
    cacheStates.addAll(states)
}

private suspend fun downloadSingle(
    context: android.content.Context,
    type: GeoFileType,
    url: String,
    stateStore: M2mStateStore,
    cacheStates: MutableList<GeoCacheState>,
    workingTypes: MutableList<GeoFileType>,
    downloadProgress: MutableMap<String, Float>,
    downloadError: MutableMap<String, String?>,
    snackbarHostState: SnackbarHostState,
) {
    if (url.isBlank()) {
        snackbarHostState.showSnackbar("URL 为空")
        return
    }
    downloadError[type.key] = null
    downloadProgress[type.key] = 0f
    workingTypes.add(type)
    try {
        val proxy = stateStore.getDownloadProxy()
        val result = GeoFileManager.downloadFile(context, type, url, proxy) { downloaded, total ->
            if (total > 0) {
                downloadProgress[type.key] = downloaded.toFloat() / total.toFloat()
            }
        }
        result.onSuccess {
            downloadProgress[type.key] = 1f
            snackbarHostState.showSnackbar(
                context.getString(R.string.geo_download_success, type.label),
            )
        }.onFailure { e ->
            downloadError[type.key] = e.message
            snackbarHostState.showSnackbar(
                context.getString(R.string.geo_download_failed, type.label, e.message ?: ""),
            )
        }
        refreshStates(context, cacheStates)
    } finally {
        workingTypes.remove(type)
    }
}

private suspend fun batchDownload(
    context: android.content.Context,
    geoConfig: GeoConfig,
    stateStore: M2mStateStore,
    cacheStates: MutableList<GeoCacheState>,
    workingTypes: MutableList<GeoFileType>,
    downloadProgress: MutableMap<String, Float>,
    downloadError: MutableMap<String, String?>,
    snackbarHostState: SnackbarHostState,
) {
    val types = GeoFileType.all.filter { type ->
        val url = GeoFileManager.getEffectiveUrl(type, GeoFileManager.resolveUrl(type, geoConfig), stateStore)
        url.isNotBlank()
    }
    if (types.isEmpty()) {
        snackbarHostState.showSnackbar("没有可下载的 URL")
        return
    }
    var successCount = 0
    var failCount = 0
    for (type in types) {
        downloadError[type.key] = null
        downloadProgress[type.key] = 0f
        workingTypes.add(type)
        val url = GeoFileManager.getEffectiveUrl(type, GeoFileManager.resolveUrl(type, geoConfig), stateStore)
        val proxy = stateStore.getDownloadProxy()
        val result = GeoFileManager.downloadFile(context, type, url, proxy) { downloaded, total ->
            if (total > 0) {
                downloadProgress[type.key] = downloaded.toFloat() / total.toFloat()
            }
        }
        result.onSuccess {
            downloadProgress[type.key] = 1f
            successCount++
        }.onFailure { e ->
            downloadError[type.key] = e.message
            failCount++
        }
        workingTypes.remove(type)
    }
    refreshStates(context, cacheStates)
    val msg = if (failCount == 0) {
        "批量下载完成：$successCount 个成功"
    } else {
        "批量下载完成：$successCount 个成功，$failCount 个失败"
    }
    snackbarHostState.showSnackbar(msg)
}

private suspend fun deleteSingle(
    context: android.content.Context,
    type: GeoFileType,
    cacheStates: MutableList<GeoCacheState>,
    snackbarHostState: SnackbarHostState,
) = withContext(Dispatchers.IO) {
    GeoFileManager.deleteCache(context, type)
    refreshStates(context, cacheStates)
    snackbarHostState.showSnackbar("${type.label} 缓存已删除")
}

private suspend fun deleteAll(
    context: android.content.Context,
    cacheStates: MutableList<GeoCacheState>,
    snackbarHostState: SnackbarHostState,
) = withContext(Dispatchers.IO) {
    GeoFileManager.deleteAllCache(context)
    refreshStates(context, cacheStates)
    snackbarHostState.showSnackbar("所有 Geo 数据缓存已删除")
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    }
}


