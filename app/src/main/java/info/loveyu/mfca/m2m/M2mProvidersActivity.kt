@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.m2m

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
import androidx.compose.material3.MaterialTheme
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
import info.loveyu.mfca.ui.theme.MfcaTheme
import kotlinx.coroutines.launch

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
    var detailProvider by remember { mutableStateOf<M2mProviderInfo?>(null) }

    LaunchedEffect(Unit) {
        refreshProviders(providers)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.providers_manage_title)) },
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
                                text = { Text(stringResource(R.string.providers_update_all_proxies)) },
                                onClick = {
                                    showOverflowMenu = false
                                    scope.launch {
                                        updateAll(
                                            context, M2mProviderType.Proxy, providers,
                                            workingNames, updateError, snackbarHostState
                                        )
                                    }
                                },
                                enabled = workingNames.isEmpty(),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.providers_update_all_rules)) },
                                onClick = {
                                    showOverflowMenu = false
                                    scope.launch {
                                        updateAll(
                                            context, M2mProviderType.Rule, providers,
                                            workingNames, updateError, snackbarHostState
                                        )
                                    }
                                },
                                enabled = workingNames.isEmpty(),
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (providers.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.providers_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }
            items(providers, key = { it.name }) { provider ->
                ProviderCard(
                    provider = provider,
                    isWorking = provider.name in workingNames,
                    errorText = updateError[provider.name],
                    onUpdate = {
                        scope.launch {
                            updateSingleProvider(
                                context, provider, providers,
                                workingNames, updateError, snackbarHostState
                            )
                        }
                    },
                    onShowDetail = { detailProvider = provider },
                )
            }
        }
    }

    detailProvider?.let { provider ->
        ProviderDetailDialog(
            provider = provider,
            onDismiss = { detailProvider = null },
        )
    }
}
