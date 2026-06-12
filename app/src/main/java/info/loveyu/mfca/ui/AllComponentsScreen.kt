package info.loveyu.mfca.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import info.loveyu.mfca.ui.component.ComponentDetailSheet
import info.loveyu.mfca.ui.component.ComponentStatus
import info.loveyu.mfca.ui.component.ComponentType
import info.loveyu.mfca.ui.component.getAllComponentStatuses
import info.loveyu.mfca.ui.component.getComponentTypeOrder

private data class AllComponentsSelectionKey(
    val id: String,
    val type: ComponentType
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllComponentsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    var selectedComponentKey by remember { mutableStateOf<AllComponentsSelectionKey?>(null) }
    val networkStateVersion by LinkManager.networkStateVersion.collectAsState()

    LaunchedEffect(Unit) {
        LinkManager.refreshNetworkState()
    }

    val allComponents = remember(networkStateVersion, ForwardService.isRunning, ForwardService.currentConfig) {
        getAllComponentStatuses(context)
    }
    val selectedComponent = remember(selectedComponentKey, allComponents) {
        val key = selectedComponentKey ?: return@remember null
        allComponents.find { it.id == key.id && it.type == key.type }
    }
    val groupedComponents = remember(allComponents) {
        allComponents
            .groupBy { it.type }
            .toList()
            .sortedBy { getComponentTypeOrder(it.first) }
            .map { (type, items) ->
                type to items.sortedWith(compareByDescending<ComponentStatus> { it.isEnabled }.thenBy { it.name })
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.all_components_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { innerPadding ->
        if (groupedComponents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_components_configured),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                groupedComponents.forEach { (type, components) ->
                    item(key = "header-${type.name}") {
                        ComponentGroupHeader(type = type, count = components.size)
                    }
                    items(
                        items = components,
                        key = { "${it.type.name}-${it.id}" }
                    ) { component ->
                        ComponentListItem(
                            component = component,
                            onClick = {
                                selectedComponentKey = AllComponentsSelectionKey(component.id, component.type)
                            }
                        )
                    }
                }
            }
        }
    }

    if (selectedComponent != null) {
        ComponentDetailSheet(
            component = selectedComponent,
            sheetState = sheetState,
            onDismiss = { selectedComponentKey = null }
        )
    }
}
