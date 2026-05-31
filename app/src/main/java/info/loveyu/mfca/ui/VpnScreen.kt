package info.loveyu.mfca.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import info.loveyu.mfca.R
import info.loveyu.mfca.config.VpnAccessControlMode
import info.loveyu.mfca.vpn.MfcaVpnService
import info.loveyu.mfca.vpn.VpnCandidateState
import info.loveyu.mfca.vpn.VpnManager
import info.loveyu.mfca.vpn.VpnRuntimeStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnTopBar() {
    CenterAlignedTopAppBar(title = { Text(stringResource(R.string.tab_vpn)) })
}

@Composable
fun VpnScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val uiState by VpnManager.state.collectAsState()
    var editingCandidate by remember { mutableStateOf<VpnCandidateState?>(null) }
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
                        VpnStatusChip(
                            label = context.getString(
                                R.string.vpn_access_summary,
                                candidate.effectiveAccessControlMode.name,
                                candidate.effectivePackages.size,
                            ),
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
                        OutlinedButton(onClick = { editingCandidate = candidate }) {
                            Text(stringResource(R.string.vpn_edit_apps))
                        }
                    }
                }
            }
        }
    }

    editingCandidate?.let { candidate ->
        VpnAccessControlDialog(
            candidate = candidate,
            onDismiss = { editingCandidate = null },
            onSave = { mode, packages ->
                VpnManager.updateAccessControl(candidate.config.name, mode, packages)
                if (uiState.isEnabled) {
                    ContextCompat.startForegroundService(
                        context,
                        MfcaVpnService.refreshIntent(context, forceRestart = true),
                    )
                }
                editingCandidate = null
            },
        )
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

@Composable
private fun VpnAccessControlDialog(
    candidate: VpnCandidateState,
    onDismiss: () -> Unit,
    onSave: (VpnAccessControlMode, List<String>) -> Unit,
) {
    val context = LocalContext.current
    val apps by produceState(initialValue = emptyList<InstalledAppItem>(), context) {
        value = loadInstalledApps(context)
    }
    var searchText by remember { mutableStateOf("") }
    var selectedMode by remember(candidate.config.name) { mutableStateOf(candidate.effectiveAccessControlMode) }
    var selectedPackages by remember(candidate.config.name) { mutableStateOf(candidate.effectivePackages.toMutableSet()) }

    val filteredApps = remember(apps, searchText) {
        val query = searchText.trim()
        if (query.isBlank()) {
            apps
        } else {
            apps.filter { app ->
                app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(selectedMode, selectedPackages.toList().sorted()) }) {
                Text(stringResource(R.string.vpn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.vpn_cancel))
            }
        },
        title = { Text(candidate.config.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VpnAccessControlMode.entries.forEach { mode ->
                        FilterChip(
                            selected = selectedMode == mode,
                            onClick = { selectedMode = mode },
                            label = { Text(mode.name) },
                        )
                    }
                }
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.vpn_search_apps)) },
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = app.packageName in selectedPackages,
                                onCheckedChange = { checked ->
                                    selectedPackages = selectedPackages.toMutableSet().apply {
                                        if (checked) {
                                            add(app.packageName)
                                        } else {
                                            remove(app.packageName)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

private data class InstalledAppItem(
    val label: String,
    val packageName: String,
)

private fun loadInstalledApps(context: Context): List<InstalledAppItem> {
    val packageManager = context.packageManager
    return getInstalledApplications(context).mapNotNull { app ->
        if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
            null
        } else {
            InstalledAppItem(
                label = packageManager.getApplicationLabel(app).toString(),
                packageName = app.packageName,
            )
        }
    }.sortedBy { it.label.lowercase() }
}

private fun getInstalledApplications(context: Context): List<ApplicationInfo> {
    val packageManager = context.packageManager
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        packageManager.getInstalledApplications(android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getInstalledApplications(0)
    }
}
