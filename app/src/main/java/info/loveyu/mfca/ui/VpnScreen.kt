@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import info.loveyu.mfca.R
import info.loveyu.mfca.vpn.MfcaVpnService
import info.loveyu.mfca.vpn.VpnAppSelectActivity
import info.loveyu.mfca.vpn.VpnCandidateSettingsActivity
import info.loveyu.mfca.vpn.VpnLogActivity
import info.loveyu.mfca.vpn.VpnManager
import info.loveyu.mfca.vpn.VpnRuntimeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VpnScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val uiState by VpnManager.state.collectAsState()
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
                ContextCompat.startForegroundService(context, MfcaVpnService.enableIntent(context))
            } else {
                VpnManager.updateRuntimeStatus(VpnRuntimeStatus.error, context.getString(R.string.vpn_permission_denied))
            }
        }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        item {
            ElevatedCard {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.vpn_runtime_title),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = uiState.statusMessage.ifBlank { stringResource(R.string.vpn_runtime_idle) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = uiState.isEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    val prepareIntent = VpnService.prepare(context)
                                    if (prepareIntent != null) {
                                        vpnPermissionLauncher.launch(prepareIntent)
                                    } else {
                                        ContextCompat.startForegroundService(context, MfcaVpnService.enableIntent(context))
                                    }
                                } else {
                                    context.startService(MfcaVpnService.disableIntent(context))
                                }
                            },
                        )
                    }

                    if (uiState.runtimeStatus in setOf(VpnRuntimeStatus.preparing, VpnRuntimeStatus.starting, VpnRuntimeStatus.stopping)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        VpnStatusChip(label = runtimeStatusLabel(uiState.runtimeStatus))
                        uiState.activeCandidateName?.let {
                            VpnStatusChip(label = context.getString(R.string.vpn_active_candidate, it))
                        } ?: VpnStatusChip(label = stringResource(R.string.vpn_no_active_candidate))
                        uiState.runningCandidateName?.let {
                            VpnStatusChip(label = context.getString(R.string.vpn_running_candidate, it))
                        } ?: VpnStatusChip(label = stringResource(R.string.vpn_not_running))
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
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(onClick = { VpnManager.refresh() }) {
                            Text(stringResource(R.string.vpn_refresh))
                        }
                        Button(
                            enabled = uiState.isEnabled && uiState.activeCandidateName != null,
                            onClick = {
                                ContextCompat.startForegroundService(
                                    context,
                                    MfcaVpnService.refreshIntent(context, forceRestart = true),
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
                        OutlinedButton(onClick = { context.startActivity(VpnLogActivity.intent(context)) }) {
                            Text(stringResource(R.string.vpn_view_log))
                        }
                        IconButton(
                            onClick = {
                                downloadProxyText = uiState.downloadProxy
                                showDownloadProxySheet = true
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.vpn_download_proxy),
                            )
                        }
                    }

                    // Core plugin section
                    if (uiState.hasVpnConfig) {
                        HorizontalDivider()
                        Text(
                            text = stringResource(R.string.vpn_core_plugin_title),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            VpnStatusChip(
                                label = stringResource(
                                    if (uiState.coreState.isReady) R.string.vpn_core_ready else R.string.vpn_core_missing,
                                ),
                            )
                        }
                        if (coreActionError != null) {
                            Text(
                                text = coreActionError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (uiState.m2mCoreUrl.isNotBlank()) {
                                FilledTonalButton(
                                    enabled = !coreActionWorking,
                                    onClick = {
                                        coreActionWorking = true
                                        coreActionError = null
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                VpnManager.downloadCorePlugin(context)
                                            }
                                            result.onFailure { e ->
                                                coreActionError = e.message ?: e.toString()
                                            }
                                            VpnManager.refresh()
                                            coreActionWorking = false
                                        }
                                    },
                                ) {
                                    if (coreActionWorking) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text(
                                            stringResource(
                                                if (uiState.coreState.isReady) R.string.vpn_core_redownload else R.string.vpn_core_download,
                                            ),
                                        )
                                    }
                                }
                            }
                            if (uiState.coreState.isReady) {
                                OutlinedButton(
                                    enabled = !coreActionWorking,
                                    onClick = {
                                        coreActionWorking = true
                                        coreActionError = null
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                VpnManager.deleteCorePlugin(context)
                                            }
                                            result.onFailure { e ->
                                                coreActionError = e.message ?: e.toString()
                                            }
                                            VpnManager.refresh()
                                            coreActionWorking = false
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.vpn_core_delete))
                                }
                            }
                        }
                    }
                }
            }
        }

        items(uiState.candidates, key = { it.config.name }) { candidate ->
            ElevatedCard {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(candidate.config.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                candidate.config.configUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        VpnStatusChip(
                            label = stringResource(
                                if (candidate.isAvailable) {
                                    R.string.vpn_candidate_available
                                } else {
                                    R.string.vpn_candidate_unavailable
                                },
                            ),
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (candidate.isSelected) {
                            VpnStatusChip(label = stringResource(R.string.vpn_selected))
                        }
                        if (uiState.runningCandidateName == candidate.config.name) {
                            VpnStatusChip(label = stringResource(R.string.vpn_candidate_running_chip))
                        }
                        VpnStatusChip(
                            label = stringResource(
                                if (candidate.coreState.isReady) R.string.vpn_core_ready else R.string.vpn_core_missing,
                            ),
                        )
                        AssistChip(
                            onClick = { context.startActivity(VpnAppSelectActivity.intent(context, candidate.config.name)) },
                            label = {
                                Text(
                                    context.getString(
                                        R.string.vpn_access_summary,
                                        candidate.effectiveAccessControlMode.name,
                                        candidate.effectivePackages.size,
                                    ),
                                )
                            },
                        )
                    }

                    candidate.availabilityReason?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()

                    // Config cache section
                    val cacheState = candidate.configCacheState
                    val isWorking = workingConfigCandidateName == candidate.config.name
                    Text(
                        text = stringResource(R.string.vpn_config_cache_title),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        VpnStatusChip(
                            label = stringResource(
                                if (cacheState.isCached) R.string.vpn_config_cached else R.string.vpn_config_not_cached,
                            ),
                        )
                        if (cacheState.isCached) {
                            val sdf = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()) }
                            cacheState.lastUpdatedMs?.let { ms ->
                                VpnStatusChip(
                                    label = stringResource(
                                        R.string.vpn_config_last_updated,
                                        sdf.format(java.util.Date(ms)),
                                    ),
                                )
                            }
                            val nextMs = cacheState.nextRefreshMs
                            if (nextMs != null && nextMs > 0) {
                                VpnStatusChip(
                                    label = stringResource(
                                        R.string.vpn_config_next_refresh,
                                        sdf.format(java.util.Date(nextMs)),
                                    ),
                                )
                            } else {
                                VpnStatusChip(label = stringResource(R.string.vpn_config_no_auto_refresh))
                            }
                        }
                    }
                    if (configActionErrorCandidateName == candidate.config.name && configActionError != null) {
                        Text(
                            text = configActionError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FilledTonalButton(
                            enabled = !isWorking,
                            onClick = {
                                workingConfigCandidateName = candidate.config.name
                                configActionError = null
                                configActionErrorCandidateName = null
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        VpnManager.downloadConfig(context, candidate.config.name)
                                    }
                                    result.onFailure { e ->
                                        configActionError = e.message ?: e.toString()
                                        configActionErrorCandidateName = candidate.config.name
                                    }
                                    VpnManager.refresh()
                                    workingConfigCandidateName = null
                                }
                            },
                        ) {
                            if (isWorking) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(
                                    stringResource(
                                        if (cacheState.isCached) R.string.vpn_config_redownload else R.string.vpn_config_download,
                                    ),
                                )
                            }
                        }
                        if (cacheState.isCached) {
                            OutlinedButton(
                                enabled = !isWorking,
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            VpnManager.deleteConfigCache(context, candidate.config.name)
                                        }
                                        VpnManager.refresh()
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.vpn_config_delete))
                            }
                        }
                    }
                    HorizontalDivider()

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            enabled = candidate.isAvailable,
                            onClick = {
                                VpnManager.selectCandidate(candidate.config.name)
                                if (uiState.isEnabled) {
                                    ContextCompat.startForegroundService(
                                        context,
                                        MfcaVpnService.refreshIntent(context, forceRestart = true),
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
                        OutlinedButton(onClick = { context.startActivity(VpnAppSelectActivity.intent(context, candidate.config.name)) }) {
                            Text(stringResource(R.string.vpn_edit_apps))
                        }
                        OutlinedButton(onClick = { context.startActivity(VpnCandidateSettingsActivity.intent(context, candidate.config.name)) }) {
                            Text(stringResource(R.string.vpn_settings))
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
                            VpnManager.setDownloadProxyOverride(downloadProxyText.trim().takeIf { it.isNotBlank() })
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
private fun VpnStatusChip(label: String) {
    AssistChip(onClick = { }, label = { Text(label) })
}

@Composable
private fun VpnInfoLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun runtimeStatusLabel(status: VpnRuntimeStatus): String {
    return stringResource(
        when (status) {
            VpnRuntimeStatus.disabled -> R.string.vpn_status_disabled
            VpnRuntimeStatus.idle -> R.string.vpn_status_idle
            VpnRuntimeStatus.preparing -> R.string.vpn_status_preparing
            VpnRuntimeStatus.prepared -> R.string.vpn_status_prepared
            VpnRuntimeStatus.starting -> R.string.vpn_status_starting
            VpnRuntimeStatus.running -> R.string.vpn_status_running
            VpnRuntimeStatus.stopping -> R.string.vpn_status_stopping
            VpnRuntimeStatus.error -> R.string.vpn_status_error
        },
    )
}
