@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import info.loveyu.mfca.R
import info.loveyu.mfca.m2m.core.M2mManager
import info.loveyu.mfca.m2m.models.M2mRuntimeStatus
import info.loveyu.mfca.m2m.service.MfcaM2mService
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
                ContextCompat.startForegroundService(
                    context, MfcaM2mService.enableIntent(context)
                )
            } else {
                M2mManager.updateRuntimeStatus(
                    M2mRuntimeStatus.error,
                    context.getString(R.string.vpn_permission_denied)
                )
            }
        }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        item {
            M2mRuntimeCard(
                uiState = uiState,
                coreActionWorking = coreActionWorking,
                coreActionError = coreActionError,
                vpnPermissionLauncher = vpnPermissionLauncher,
                onCoreActionWorkingChange = { coreActionWorking = it },
                onCoreActionErrorChange = { coreActionError = it },
                onOpenDownloadProxySheet = {
                    downloadProxyText = uiState.downloadProxy
                    showDownloadProxySheet = true
                },
            )
        }

        if (uiState.runtimeStatus == M2mRuntimeStatus.running) {
            item {
                M2mTrafficCard(trafficStats = trafficStats)
            }
        }

        items(uiState.candidates, key = { it.config.name }) { candidate ->
            val isWorking = workingConfigCandidateName == candidate.config.name
            M2mCandidateCard(
                candidate = candidate,
                uiState = uiState,
                isWorking = isWorking,
                actionError = if (configActionErrorCandidateName == candidate.config.name) {
                    configActionError
                } else null,
                onDownloadConfig = {
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
                onDeleteConfig = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            M2mManager.deleteConfigCache(context, candidate.config.name)
                        }
                        M2mManager.refresh()
                    }
                },
            )
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
                            M2mManager.setDownloadProxyOverride(
                                downloadProxyText.trim().takeIf { it.isNotBlank() }
                            )
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
