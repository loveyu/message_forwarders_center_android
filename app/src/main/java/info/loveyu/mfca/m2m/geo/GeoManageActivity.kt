@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.m2m.geo

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.R
import info.loveyu.mfca.m2m.core.M2mStateStore
import info.loveyu.mfca.ui.theme.MfcaTheme
import kotlinx.coroutines.launch

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
                                        batchDownload(
                                            context, geoConfig, stateStore, cacheStates,
                                            workingTypes, downloadProgress, downloadError,
                                            snackbarHostState
                                        )
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
                                        scope.launch {
                                            snackbarHostState.showSnackbar("没有已缓存的数据")
                                        }
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
                val state =
                    cacheStates.firstOrNull { it.type == type } ?: GeoCacheState(type, false)
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
                            downloadSingle(
                                context, type, effectiveUrl, stateStore, cacheStates,
                                workingTypes, downloadProgress, downloadError,
                                snackbarHostState
                            )
                        }
                    },
                    onDelete = { confirmDeleteType = type },
                    onEditUrl = {
                        editingType = type
                        editingUrl = effectiveUrl
                    },
                    onResetUrl = { confirmResetType = type },
                )
            }
        }
    }

    editingType?.let { type ->
        EditUrlDialog(
            editingUrl = editingUrl,
            onUrlChange = { editingUrl = it },
            onSave = {
                stateStore.setGeoUrlOverride(type.key, editingUrl.takeIf { it.isNotBlank() })
                editingType = null
                scope.launch { refreshStates(context, cacheStates) }
            },
            onDismiss = { editingType = null },
        )
    }

    confirmDeleteType?.let { type ->
        ConfirmDeleteSingleDialog(
            type = type,
            onConfirm = {
                confirmDeleteType = null
                scope.launch {
                    deleteSingle(context, type, cacheStates, snackbarHostState)
                }
            },
            onDismiss = { confirmDeleteType = null },
        )
    }

    if (confirmDeleteAll) {
        ConfirmDeleteAllDialog(
            onConfirm = {
                confirmDeleteAll = false
                scope.launch {
                    deleteAll(context, cacheStates, snackbarHostState)
                }
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }

    confirmResetType?.let { type ->
        ConfirmResetSingleDialog(
            type = type,
            onConfirm = {
                confirmResetType = null
                stateStore.setGeoUrlOverride(type.key, null)
                scope.launch { refreshStates(context, cacheStates) }
            },
            onDismiss = { confirmResetType = null },
        )
    }

    if (confirmResetAll) {
        ConfirmResetAllDialog(
            onConfirm = {
                confirmResetAll = false
                GeoFileType.all.forEach { stateStore.setGeoUrlOverride(it.key, null) }
                scope.launch { refreshStates(context, cacheStates) }
            },
            onDismiss = { confirmResetAll = false },
        )
    }
}
