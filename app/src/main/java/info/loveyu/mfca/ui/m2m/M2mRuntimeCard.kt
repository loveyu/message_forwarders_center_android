@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.ui.m2m

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import info.loveyu.mfca.R
import info.loveyu.mfca.m2m.ui.GeoManageActivity
import info.loveyu.mfca.m2m.ui.M2mAppSelectActivity
import info.loveyu.mfca.m2m.ui.M2mCandidateSettingsActivity
import info.loveyu.mfca.m2m.ui.M2mLogActivity
import info.loveyu.mfca.m2m.core.M2mManager
import info.loveyu.mfca.m2m.models.M2mRuntimeStatus
import info.loveyu.mfca.m2m.models.M2mUiState
import info.loveyu.mfca.m2m.service.MfcaM2mService
import info.loveyu.mfca.m2m.ui.M2mProvidersActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun M2mRuntimeCard(
    uiState: M2mUiState,
    coreActionWorking: Boolean,
    coreActionError: String?,
    vpnPermissionLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>,
    onCoreActionWorkingChange: (Boolean) -> Unit,
    onCoreActionErrorChange: (String?) -> Unit,
    onOpenDownloadProxySheet: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
                        text = coreActionError,
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
                            if (uiState.runningCandidateName == null) R.string.vpn_apply_selected
                            else R.string.vpn_restart_selected
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
                                                if (uiState.coreState.isReady) R.string.vpn_core_redownload
                                                else R.string.vpn_core_download
                                            ),
                                        )
                                    }
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onCoreActionWorkingChange(true)
                                    onCoreActionErrorChange(null)
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            M2mManager.downloadCorePlugin(context)
                                        }
                                        result.onFailure { e ->
                                            onCoreActionErrorChange(e.message ?: e.toString())
                                        }
                                        M2mManager.refresh()
                                        onCoreActionWorkingChange(false)
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
                                    onCoreActionWorkingChange(true)
                                    onCoreActionErrorChange(null)
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            M2mManager.deleteCorePlugin(context)
                                        }
                                        result.onFailure { e ->
                                            onCoreActionErrorChange(e.message ?: e.toString())
                                        }
                                        M2mManager.refresh()
                                        onCoreActionWorkingChange(false)
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
                            text = { Text(stringResource(R.string.geo_manage_menu)) },
                            onClick = {
                                showOverflowMenu = false
                                context.startActivity(
                                    android.content.Intent(context, GeoManageActivity::class.java),
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.providers_manage_menu)) },
                            onClick = {
                                showOverflowMenu = false
                                context.startActivity(
                                    android.content.Intent(context, M2mProvidersActivity::class.java),
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vpn_download_proxy)) },
                            onClick = {
                                showOverflowMenu = false
                                onOpenDownloadProxySheet()
                            },
                        )
                    }
                }
            }
        }
    }
}
