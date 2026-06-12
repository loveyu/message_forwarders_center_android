@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.R
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.ui.allcomponents.AllComponentsActivity
import info.loveyu.mfca.ui.component.ComponentDetailSheet
import info.loveyu.mfca.ui.component.getAllComponentStatuses
import info.loveyu.mfca.util.Preferences

@Composable
fun MainScreen(
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    contentPadding: PaddingValues = PaddingValues()
) {
    val context = LocalContext.current
    val preferences = remember { Preferences(context) }
    var isRunning by remember { mutableStateOf(ForwardService.isRunning) }
    var isStarting by remember { mutableStateOf(ForwardService.isStarting) }
    var componentStateVersion by remember { mutableIntStateOf(0) }
    val detailSheetState = rememberModalBottomSheetState()
    var showComponentSheet by remember { mutableStateOf(false) }
    var selectedComponentKey by remember { mutableStateOf<ComponentSelectionKey?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        isRunning = ForwardService.isRunning
        isStarting = ForwardService.isStarting
        ForwardService.onStatsChanged = {
            isRunning = ForwardService.isRunning
            isStarting = ForwardService.isStarting
            componentStateVersion++
        }
        ForwardService.onStartFailed = { errorMsg ->
            Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ForwardService.onStatsChanged = null
            ForwardService.onStartFailed = null
        }
    }

    LaunchedEffect(isRunning) {
        if (!isRunning) {
            showComponentSheet = false
            selectedComponentKey = null
        }
    }

    LaunchedEffect(showComponentSheet) {
        if (showComponentSheet) {
            LinkManager.refreshNetworkState()
        }
    }

    val networkStateVersion by LinkManager.networkStateVersion.collectAsState()
    val hasConfig = ForwardService.currentConfig != null
    val allComponents = remember(
        isRunning, networkStateVersion, hasConfig, componentStateVersion
    ) {
        if (!isRunning && !hasConfig) emptyList() else getAllComponentStatuses(context)
    }
    val enabledComponents = remember(allComponents) { allComponents.filter { it.isEnabled } }
    val disabledComponents = remember(allComponents) { allComponents.filter { !it.isEnabled } }
    val selectedComponent = remember(selectedComponentKey, allComponents) {
        val key = selectedComponentKey ?: return@remember null
        allComponents.find { it.id == key.id && it.type == key.type }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isStarting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (isRunning) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape
                                )
                        )
                    }
                    Text(
                        text = when {
                            isStarting -> "启动中..."
                            isRunning -> "${stringResource(R.string.status_running)} · R${ForwardService.receivedCount} S${ForwardService.forwardedCount}"
                            else -> stringResource(R.string.status_stopped)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            isStarting -> MaterialTheme.colorScheme.primary
                            isRunning -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (isStarting) {
                    Button(onClick = {}, enabled = false) {
                        Text(stringResource(R.string.start_service))
                    }
                } else if (isRunning) {
                    Button(onClick = onStopServer) {
                        Text(stringResource(R.string.stop_service))
                    }
                } else {
                    Button(onClick = onStartServer) {
                        Text(stringResource(R.string.start_service))
                    }
                }
            }
        }

        if (isRunning) {
            val totalCount = enabledComponents.size + disabledComponents.size
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.component_status),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    context.startActivity(
                                        Intent(context, AllComponentsActivity::class.java)
                                    )
                                }
                            )
                        }
                        if (totalCount > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (enabledComponents.isNotEmpty()) {
                                    ComponentCountBadge(
                                        count = enabledComponents.size,
                                        label = "启用",
                                        isEnabled = true
                                    )
                                }
                                if (disabledComponents.isNotEmpty()) {
                                    ComponentCountBadge(
                                        count = disabledComponents.size,
                                        label = "未启用",
                                        isEnabled = false
                                    )
                                }
                            }
                        }
                    }
                    if (totalCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            enabledComponents.take(5).forEach { component ->
                                ComponentChip(
                                    component = component, isEnabled = true, onClick = {
                                        selectedComponentKey = ComponentSelectionKey(
                                            component.id, component.type
                                        )
                                        showComponentSheet = true
                                    }
                                )
                            }
                            disabledComponents.take(5).forEach { component ->
                                ComponentChip(
                                    component = component, isEnabled = false, onClick = {
                                        selectedComponentKey = ComponentSelectionKey(
                                            component.id, component.type
                                        )
                                        showComponentSheet = true
                                    }
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "暂无组件",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        LogSection(
            preferences = preferences,
            listState = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 8.dp)
        )
    }

    if (showComponentSheet) {
        ComponentDetailSheet(
            component = selectedComponent,
            sheetState = detailSheetState,
            onDismiss = {
                showComponentSheet = false
                selectedComponentKey = null
            }
        )
    }
}
