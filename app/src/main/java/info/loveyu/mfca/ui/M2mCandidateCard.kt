@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.ui

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import info.loveyu.mfca.R
import info.loveyu.mfca.m2m.ui.M2mAppSelectActivity
import info.loveyu.mfca.m2m.ui.M2mCandidateSettingsActivity
import info.loveyu.mfca.m2m.models.M2mCandidateState
import info.loveyu.mfca.m2m.core.M2mManager
import info.loveyu.mfca.m2m.models.M2mUiState
import info.loveyu.mfca.m2m.service.MfcaM2mService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun M2mCandidateCard(
    candidate: M2mCandidateState,
    uiState: M2mUiState,
    isWorking: Boolean,
    actionError: String?,
    onDownloadConfig: () -> Unit,
    onDeleteConfig: () -> Unit,
) {
    val context = LocalContext.current
    val sdf = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    ElevatedCard {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
        ) {
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
                    Text(
                        candidate.config.name,
                        style = MaterialTheme.typography.titleMedium
                    )
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
                        if (candidate.isAvailable) R.string.vpn_candidate_available
                        else R.string.vpn_candidate_unavailable,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (candidate.isAvailable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }

            candidate.availabilityReason?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val cacheState = candidate.configCacheState
            if (cacheState.isCached) {
                val cacheInfo = buildString {
                    append(stringResource(R.string.vpn_config_cached))
                    cacheState.lastUpdatedMs?.let { ms ->
                        append(" · ")
                        append(sdf.format(Date(ms)))
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

            if (actionError != null) {
                Text(
                    text = actionError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

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
                        if (candidate.isSelected) stringResource(R.string.vpn_selected)
                        else stringResource(R.string.vpn_select),
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
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text(
                                        stringResource(
                                            if (cacheState.isCached) R.string.vpn_config_redownload
                                            else R.string.vpn_config_download,
                                        ),
                                    )
                                }
                            },
                            onClick = {
                                showMenu = false
                                if (!isWorking) onDownloadConfig()
                            },
                            enabled = !isWorking && !uiState.isBusy,
                        )
                        if (cacheState.isCached) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.vpn_config_delete)) },
                                onClick = {
                                    showMenu = false
                                    onDeleteConfig()
                                },
                                enabled = !isWorking && !uiState.isBusy,
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vpn_edit_apps)) },
                            onClick = {
                                showMenu = false
                                context.startActivity(
                                    M2mAppSelectActivity.intent(context, candidate.config.name)
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vpn_settings)) },
                            onClick = {
                                showMenu = false
                                context.startActivity(
                                    M2mCandidateSettingsActivity.intent(
                                        context, candidate.config.name
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
