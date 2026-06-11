@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import info.loveyu.mfca.R
import info.loveyu.mfca.m2m.MfcaM2mService
import info.loveyu.mfca.m2m.M2mAppSelectActivity
import info.loveyu.mfca.m2m.M2mCandidateSettingsActivity
import info.loveyu.mfca.m2m.M2mLogActivity
import info.loveyu.mfca.m2m.M2mManager
import info.loveyu.mfca.m2m.M2mRuntimeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun M2mScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val uiState by M2mManager.state.collectAsState()
    val trafficStats by M2mManager.trafficStats.collectAsState()
    val scope = rememberCoroutineScope()
    var workingConfigCandidateName by remember { mutableStateOf<String?>(null) }
    var coreActionWorking by remember { mutableStateOf(false) }
    var coreActionError by remember { mutableStateOf<String?>(null) }
    var configActionError by remember { mutableStateOf<String?>(null) }
    var configActionErrorCandidateName by remember { mutableStateOf<String?>(null) }
    var showDownloadProxySheet by remember { mutableStateOf(false) }
    var downloadProxyText by remember { mutableStateOf(uiState.downloadProxy) }
    val vpnPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                ContextCompat.startForegroundService(context, MfcaM2mService.enableIntent(context))
            } else {
                M2mManager.updateRuntimeStatus(M2mRuntimeStatus.error, context.getString(R.string.vpn_permission_denied))
            }
        }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // ── Runtime Card ──
        item {
            ElevatedCard {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                ) {
                    val runningName = uiState.runningCandidateName
                    if (uiState.runtimeStatus == M2mRuntimeStatus.running && runningName != null) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_running_title_format, runningName),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Switch(
                                checked = true,
                                onCheckedChange = {
                                    M2mManager.setEnabled(false)
                                    context.startService(MfcaM2mService.disableIntent(context))
                                },
                            )
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_runtime_title),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Switch(
                                checked = uiState.isEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        val prepareIntent = VpnService.prepare(context)
                                        if (prepareIntent != null) {
                                            vpnPermissionLauncher.launch(prepareIntent)
                                        } else {
                                            ContextCompat.startForegroundService(
                                                context,
                                                MfcaM2mService.enableIntent(context),
                                            )
                                        }
                                    } else {
                                        M2mManager.setEnabled(false)
                                        context.startService(MfcaM2mService.disableIntent(context))
                                    }
                                },
                            )
                        }

                        if (uiState.isBusy) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = runtimeStatusLabel(uiState.runtimeStatus),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            uiState.activeCandidateName?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        if (uiState.hasVpnConfig && !uiState.coreState.isReady) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Text(
                                    text = stringResource(R.string.vpn_core_unavailable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }

                        if (uiState.isRuntimeOutOfSync) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Text(
                                    text = stringResource(R.string.vpn_runtime_out_of_sync),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }

                        if (coreActionError != null) {
                            Text(
                                text = coreActionError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            enabled = uiState.isEnabled && uiState.activeCandidateName != null && !uiState.isBusy,
                            onClick = {
                                ContextCompat.startForegroundService(
                                    context,
                                    MfcaM2mService.refreshIntent(context, forceRestart = true),
                                )
                            },
                        ) {
                            Text(
                                stringResource(
                                    if (uiState.runningCandidateName == null) {
                                        R.string.vpn_apply_selected
                                    } else {
                                        R.string.vpn_restart_selected
                                    },
                                ),
                            )
                        }

                        Box(modifier = Modifier.weight(1f))

                        var showOverflowMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_options),
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                if (uiState.hasVpnConfig && uiState.m2mCoreUrl.isNotBlank()) {
                                    DropdownMenuItem(
                                        text = {
                                            if (coreActionWorking) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            } else {
                                                Text(
                                                    stringResource(
                                                        if (uiState.coreState.isReady) R.string.vpn_core_redownload else R.string.vpn_core_download,
                                                    ),
                                                )
                                            }
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            coreActionWorking = true
                                            coreActionError = null
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    M2mManager.downloadCorePlugin(context)
                                                }
                                                result.onFailure { e ->
                                                    coreActionError = e.message ?: e.toString()
                                                }
                                                M2mManager.refresh()
                                                coreActionWorking = false
                                            }
                                        },
                                        enabled = !coreActionWorking && !uiState.isBusy,
                                    )
                                }
                                if (uiState.hasVpnConfig && uiState.coreState.isReady) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.vpn_core_delete)) },
                                        onClick = {
                                            showOverflowMenu = false
                                            coreActionWorking = true
                                            coreActionError = null
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    M2mManager.deleteCorePlugin(context)
                                                }
                                                result.onFailure { e ->
                                                    coreActionError = e.message ?: e.toString()
                                                }
                                                M2mManager.refresh()
                                                coreActionWorking = false
                                            }
                                        },
                                        enabled = !coreActionWorking && !uiState.isBusy,
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.vpn_view_log)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        context.startActivity(M2mLogActivity.intent(context))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.vpn_download_proxy)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        downloadProxyText = uiState.downloadProxy
                                        showDownloadProxySheet = true
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Traffic Card (only when VPN running) ──
        if (uiState.runtimeStatus == M2mRuntimeStatus.running) {
            item {
                ElevatedCard {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.vpn_traffic_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "↓ ${formatSpeed(trafficStats?.rxSpeed ?: 0L)}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = stringResource(R.string.vpn_download_speed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "↑ ${formatSpeed(trafficStats?.txSpeed ?: 0L)}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Text(
                                    text = stringResource(R.string.vpn_upload_speed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.vpn_total_download, formatBytes(trafficStats?.totalRxBytes ?: 0L)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.vpn_total_upload, formatBytes(trafficStats?.totalTxBytes ?: 0L)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ── Candidate Cards ──
        items(uiState.candidates, key = { it.config.name }) { candidate ->
            ElevatedCard {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                ) {
                    // Header: name + status
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(candidate.config.name, style = MaterialTheme.typography.titleMedium)
                            if (candidate.isSelected) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        text = stringResource(R.string.vpn_selected),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (uiState.runningCandidateName == candidate.config.name) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        text = stringResource(R.string.vpn_candidate_running_chip),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(
                                if (candidate.isAvailable) R.string.vpn_candidate_available else R.string.vpn_candidate_unavailable,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (candidate.isAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }

                    // Availability reason
                    candidate.availabilityReason?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Cache status
                    val cacheState = candidate.configCacheState
                    val isWorking = workingConfigCandidateName == candidate.config.name
                    if (cacheState.isCached) {
                        val sdf = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }
                        val cacheInfo = buildString {
                            append(stringResource(R.string.vpn_config_cached))
                            cacheState.lastUpdatedMs?.let { ms ->
                                append(" · ")
                                append(sdf.format(java.util.Date(ms)))
                            }
                        }
                        Text(
                            text = cacheInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.vpn_config_not_cached),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (configActionErrorCandidateName == candidate.config.name && configActionError != null) {
                        Text(
                            text = configActionError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    // Action row: select button + overflow menu
                    HorizontalDivider()
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            enabled = candidate.isAvailable && !uiState.isBusy,
                            onClick = {
                                M2mManager.selectCandidate(candidate.config.name)
                                if (uiState.isEnabled) {
                                    ContextCompat.startForegroundService(
                                        context,
                                        MfcaM2mService.refreshIntent(context, forceRestart = true),
                                    )
                                }
                            },
                        ) {
                            Text(
                                if (candidate.isSelected) {
                                    stringResource(R.string.vpn_selected)
                                } else {
                                    stringResource(R.string.vpn_select)
                                },
                            )
                        }
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.vpn_settings),
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        if (isWorking) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        } else {
                                            Text(
                                                stringResource(
                                                    if (cacheState.isCached) R.string.vpn_config_redownload else R.string.vpn_config_download,
                                                ),
                                            )
                                        }
                                    },
                                    onClick = {
                                        showMenu = false
                                        workingConfigCandidateName = candidate.config.name
                                        configActionError = null
                                        configActionErrorCandidateName = null
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                M2mManager.downloadConfig(context, candidate.config.name)
                                            }
                                            result.onFailure { e ->
                                                configActionError = e.message ?: e.toString()
                                                configActionErrorCandidateName = candidate.config.name
                                            }
                                            M2mManager.refresh()
                                            workingConfigCandidateName = null
                                        }
                                    },
                                    enabled = !isWorking && !uiState.isBusy,
                                )
                                if (cacheState.isCached) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.vpn_config_delete)) },
                                        onClick = {
                                            showMenu = false
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    M2mManager.deleteConfigCache(context, candidate.config.name)
                                                }
                                                M2mManager.refresh()
                                            }
                                        },
                                        enabled = !isWorking && !uiState.isBusy,
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.vpn_edit_apps)) },
                                    onClick = {
                                        showMenu = false
                                        context.startActivity(M2mAppSelectActivity.intent(context, candidate.config.name))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.vpn_settings)) },
                                    onClick = {
                                        showMenu = false
                                        context.startActivity(M2mCandidateSettingsActivity.intent(context, candidate.config.name))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDownloadProxySheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showDownloadProxySheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.vpn_download_proxy),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = downloadProxyText,
                    onValueChange = { downloadProxyText = it },
                    label = { Text(stringResource(R.string.vpn_download_proxy_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = { showDownloadProxySheet = false }) {
                        Text(stringResource(R.string.vpn_cancel))
                    }
                    Button(
                        onClick = {
                            M2mManager.setDownloadProxyOverride(downloadProxyText.trim().takeIf { it.isNotBlank() })
                            showDownloadProxySheet = false
                        },
                    ) {
                        Text(stringResource(R.string.vpn_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun runtimeStatusLabel(status: M2mRuntimeStatus): String {
    return stringResource(
        when (status) {
            M2mRuntimeStatus.disabled -> R.string.vpn_status_disabled
            M2mRuntimeStatus.idle -> R.string.vpn_status_idle
            M2mRuntimeStatus.preparing -> R.string.vpn_status_preparing
            M2mRuntimeStatus.prepared -> R.string.vpn_status_prepared
            M2mRuntimeStatus.starting -> R.string.vpn_status_starting
            M2mRuntimeStatus.running -> R.string.vpn_status_running
            M2mRuntimeStatus.stopping -> R.string.vpn_status_stopping
            M2mRuntimeStatus.error -> R.string.vpn_status_error
        },
    )
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec < 1024 -> "$bytesPerSec B/s"
        bytesPerSec < 1024 * 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
        bytesPerSec < 1024 * 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024))
        else -> String.format("%.1f GB/s", bytesPerSec / (1024.0 * 1024 * 1024))
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
