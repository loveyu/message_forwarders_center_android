@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package info.loveyu.mfca.m2m

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.R
import info.loveyu.mfca.ui.theme.MfcaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class M2mProvidersActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MfcaTheme {
                M2mProvidersScreen()
            }
        }
    }
}

@Composable
private fun M2mProvidersScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val providers = remember { mutableStateListOf<M2mProviderInfo>() }
    val workingNames = remember { mutableStateListOf<String>() }
    val updateError = remember { mutableStateMapOf<String, String?>() }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var confirmUpdateAllProxies by remember { mutableStateOf(false) }
    var confirmUpdateAllRules by remember { mutableStateOf(false) }
    var initialLoading by remember { mutableStateOf(true) }
    var detailProvider by remember { mutableStateOf<M2mProviderInfo?>(null) }

    LaunchedEffect(Unit) {
        refreshProviders(providers)
        initialLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.providers_manage_title)) },
                navigationIcon = {
                    IconButton(onClick = { (context as? Activity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
                                text = { Text(stringResource(R.string.providers_update_all_proxies)) },
                                onClick = {
                                    showOverflowMenu = false
                                    confirmUpdateAllProxies = true
                                },
                                enabled = workingNames.isEmpty() && M2mManager.isApiReady(),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.providers_update_all_rules)) },
                                onClick = {
                                    showOverflowMenu = false
                                    confirmUpdateAllRules = true
                                },
                                enabled = workingNames.isEmpty() && M2mManager.isApiReady(),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (initialLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (!M2mManager.isApiReady()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.providers_core_not_running),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (providers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.providers_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(providers, key = { it.name }) { provider ->
                    val isWorking = provider.name in workingNames
                    ProviderCard(
                        provider = provider,
                        isWorking = isWorking,
                        errorText = updateError[provider.name],
                        onUpdate = {
                            scope.launch {
                                updateSingleProvider(
                                    context = context,
                                    provider = provider,
                                    providers = providers,
                                    workingNames = workingNames,
                                    updateError = updateError,
                                    snackbarHostState = snackbarHostState,
                                )
                            }
                        },
                        onShowDetail = { detailProvider = provider },
                    )
                }
            }
        }
    }

    detailProvider?.let { ProviderDetailDialog(it) { detailProvider = null } }

    if (confirmUpdateAllProxies) {
        AlertDialog(
            onDismissRequest = { confirmUpdateAllProxies = false },
            title = { Text(stringResource(R.string.providers_update_all_proxies)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUpdateAllProxies = false
                    scope.launch {
                        updateAll(context, M2mProviderType.Proxy, providers, workingNames, updateError, snackbarHostState)
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUpdateAllProxies = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (confirmUpdateAllRules) {
        AlertDialog(
            onDismissRequest = { confirmUpdateAllRules = false },
            title = { Text(stringResource(R.string.providers_update_all_rules)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUpdateAllRules = false
                    scope.launch {
                        updateAll(context, M2mProviderType.Rule, providers, workingNames, updateError, snackbarHostState)
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUpdateAllRules = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ProviderCard(
    provider: M2mProviderInfo,
    isWorking: Boolean,
    errorText: String?,
    onUpdate: () -> Unit,
    onShowDetail: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val hasUpdatedAt = provider.hasValidUpdatedAt()

    Card(
        modifier = Modifier.combinedClickable(
            onClick = onShowDetail,
            onDoubleClick = {
                if (!isWorking && M2mManager.isApiReady()) onUpdate()
            },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = when (provider.type) {
                                M2mProviderType.Proxy -> stringResource(R.string.providers_type_proxy)
                                M2mProviderType.Rule -> stringResource(R.string.providers_type_rule)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = when (provider.vehicleType) {
                                M2mVehicleType.HTTP -> stringResource(R.string.providers_vehicle_http)
                                M2mVehicleType.File -> stringResource(R.string.providers_vehicle_file)
                                M2mVehicleType.Compatible -> stringResource(R.string.providers_vehicle_compatible)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.providers_update)) },
                            onClick = {
                                showMenu = false
                                if (!isWorking) onUpdate()
                            },
                            enabled = !isWorking && M2mManager.isApiReady(),
                            leadingIcon = {
                                if (isWorking) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            },
                        )
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (provider.type == M2mProviderType.Proxy) {
                        Text(
                            text = stringResource(R.string.providers_proxy_count, provider.proxyCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.providers_rule_count, provider.ruleCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (hasUpdatedAt) {
                        Text(
                            text = stringResource(R.string.providers_updated_at, provider.updatedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (isWorking) {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).height(2.dp),
                    strokeWidth = 2.dp,
                )
            }

            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ProviderDetailDialog(
    provider: M2mProviderInfo,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(provider.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow(
                    label = stringResource(R.string.providers_detail_type),
                    value = when (provider.type) {
                        M2mProviderType.Proxy -> stringResource(R.string.providers_type_proxy)
                        M2mProviderType.Rule -> stringResource(R.string.providers_type_rule)
                    },
                )
                DetailRow(
                    label = stringResource(R.string.providers_detail_vehicle),
                    value = when (provider.vehicleType) {
                        M2mVehicleType.HTTP -> stringResource(R.string.providers_vehicle_http)
                        M2mVehicleType.File -> stringResource(R.string.providers_vehicle_file)
                        M2mVehicleType.Compatible -> stringResource(R.string.providers_vehicle_compatible)
                    },
                )
                if (provider.type == M2mProviderType.Proxy) {
                    DetailRow(
                        label = stringResource(R.string.providers_detail_proxy_count),
                        value = stringResource(R.string.providers_proxy_count, provider.proxyCount),
                    )
                } else {
                    DetailRow(
                        label = stringResource(R.string.providers_detail_rule_count),
                        value = stringResource(R.string.providers_rule_count, provider.ruleCount),
                    )
                }
                if (provider.hasValidUpdatedAt()) {
                    DetailRow(
                        label = stringResource(R.string.providers_detail_updated),
                        value = provider.updatedAt,
                    )
                }
                if (provider.vehicleType == M2mVehicleType.HTTP && provider.subscriptionUrl.isNotBlank()) {
                    HorizontalDivider()
                    DetailRow(
                        label = stringResource(R.string.providers_detail_url),
                        value = provider.subscriptionUrl,
                        mono = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

private suspend fun refreshProviders(
    providers: MutableList<M2mProviderInfo>,
) = withContext(Dispatchers.IO) {
    val result = M2mManager.fetchProviders()
    providers.clear()
    result.onSuccess { map ->
        providers.addAll(map.values.sortedBy { it.name })
    }
}

private suspend fun updateSingleProvider(
    context: android.content.Context,
    provider: M2mProviderInfo,
    providers: MutableList<M2mProviderInfo>,
    workingNames: MutableList<String>,
    updateError: MutableMap<String, String?>,
    snackbarHostState: SnackbarHostState,
) {
    updateError[provider.name] = null
    workingNames.add(provider.name)
    try {
        val result = withContext(Dispatchers.IO) {
            M2mManager.updateProvider(provider.name, provider.type)
        }
        result.onSuccess {
            refreshProviders(providers)
            snackbarHostState.showSnackbar(
                context.getString(R.string.providers_update_success, provider.name),
            )
        }.onFailure { e ->
            updateError[provider.name] = e.message
            snackbarHostState.showSnackbar(
                context.getString(R.string.providers_update_failed, provider.name, e.message ?: ""),
            )
        }
    } finally {
        workingNames.remove(provider.name)
    }
}

private suspend fun updateAll(
    context: android.content.Context,
    type: M2mProviderType,
    providers: MutableList<M2mProviderInfo>,
    workingNames: MutableList<String>,
    updateError: MutableMap<String, String?>,
    snackbarHostState: SnackbarHostState,
) {
    val targetProviders = providers.filter { it.type == type }
    if (targetProviders.isEmpty()) {
        snackbarHostState.showSnackbar(context.getString(R.string.providers_empty))
        return
    }
    var successCount = 0
    var failCount = 0
    for (provider in targetProviders) {
        updateError[provider.name] = null
        workingNames.add(provider.name)
        val result = withContext(Dispatchers.IO) {
            M2mManager.updateProvider(provider.name, provider.type)
        }
        result.onSuccess {
            successCount++
        }.onFailure { e ->
            updateError[provider.name] = e.message
            failCount++
        }
        workingNames.remove(provider.name)
    }
    refreshProviders(providers)
    val msg = if (failCount == 0) {
        context.getString(R.string.providers_update_all_success)
    } else {
        context.getString(R.string.providers_update_all_failed, "$successCount/$failCount")
    }
    snackbarHostState.showSnackbar(msg)
}
