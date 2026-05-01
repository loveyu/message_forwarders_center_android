package info.loveyu.mfca.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.R
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.ui.theme.DisabledChipBgDark
import info.loveyu.mfca.ui.theme.DisabledChipBgLight
import info.loveyu.mfca.ui.theme.DisabledChipBorderDark
import info.loveyu.mfca.ui.theme.DisabledChipBorderLight
import info.loveyu.mfca.ui.theme.ErrorCardIconDark
import info.loveyu.mfca.ui.theme.ErrorCardIconLight
import info.loveyu.mfca.ui.theme.HttpInputChipBgDark
import info.loveyu.mfca.ui.theme.HttpInputChipBgLight
import info.loveyu.mfca.ui.theme.HttpInputChipBorderDark
import info.loveyu.mfca.ui.theme.HttpInputChipBorderLight
import info.loveyu.mfca.ui.theme.LinkChipBgDark
import info.loveyu.mfca.ui.theme.LinkChipBgLight
import info.loveyu.mfca.ui.theme.LinkChipBorderDark
import info.loveyu.mfca.ui.theme.LinkChipBorderLight
import info.loveyu.mfca.ui.theme.LinkInputChipBgDark
import info.loveyu.mfca.ui.theme.LinkInputChipBgLight
import info.loveyu.mfca.ui.theme.LinkInputChipBorderDark
import info.loveyu.mfca.ui.theme.LinkInputChipBorderLight
import info.loveyu.mfca.ui.theme.OutputChipBgDark
import info.loveyu.mfca.ui.theme.OutputChipBgLight
import info.loveyu.mfca.ui.theme.OutputChipBorderDark
import info.loveyu.mfca.ui.theme.OutputChipBorderLight
import info.loveyu.mfca.ui.theme.QueueChipBgDark
import info.loveyu.mfca.ui.theme.QueueChipBgLight
import info.loveyu.mfca.ui.theme.QueueChipBorderDark
import info.loveyu.mfca.ui.theme.QueueChipBorderLight
import info.loveyu.mfca.ui.theme.StatusDisabledDark
import info.loveyu.mfca.ui.theme.StatusDisabledLight
import info.loveyu.mfca.ui.theme.StatusErrorDark
import info.loveyu.mfca.ui.theme.StatusErrorLight
import info.loveyu.mfca.ui.theme.StatusRunningDark
import info.loveyu.mfca.ui.theme.StatusRunningLight
import info.loveyu.mfca.ui.theme.StatusWarningDark
import info.loveyu.mfca.ui.theme.StatusWarningLight

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

@Composable
private fun ComponentGroupHeader(type: ComponentType, count: Int) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = getComponentTypeName(type),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        HorizontalDivider()
    }
}

@Composable
private fun ComponentListItem(
    component: ComponentStatus,
    onClick: () -> Unit
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val backgroundColor = if (component.isEnabled) {
        when (component.type) {
            ComponentType.LINK -> if (isDark) LinkChipBgDark else LinkChipBgLight
            ComponentType.HTTP_INPUT -> if (isDark) HttpInputChipBgDark else HttpInputChipBgLight
            ComponentType.LINK_INPUT, ComponentType.RULE -> if (isDark) LinkInputChipBgDark else LinkInputChipBgLight
            ComponentType.OUTPUT -> if (isDark) OutputChipBgDark else OutputChipBgLight
            ComponentType.QUEUE -> if (isDark) QueueChipBgDark else QueueChipBgLight
        }
    } else {
        if (isDark) DisabledChipBgDark else DisabledChipBgLight
    }
    val borderColor = if (component.isEnabled) {
        when (component.type) {
            ComponentType.LINK -> if (isDark) LinkChipBorderDark else LinkChipBorderLight
            ComponentType.HTTP_INPUT -> if (isDark) HttpInputChipBorderDark else HttpInputChipBorderLight
            ComponentType.LINK_INPUT, ComponentType.RULE -> if (isDark) LinkInputChipBorderDark else LinkInputChipBorderLight
            ComponentType.OUTPUT -> if (isDark) OutputChipBorderDark else OutputChipBorderLight
            ComponentType.QUEUE -> if (isDark) QueueChipBorderDark else QueueChipBorderLight
        }
    } else {
        if (isDark) DisabledChipBorderDark else DisabledChipBorderLight
    }
    val statusColor = when {
        component.error != null -> if (isDark) StatusErrorDark else StatusErrorLight
        component.isRunning -> if (isDark) StatusRunningDark else StatusRunningLight
        component.isEnabled -> if (isDark) StatusWarningDark else StatusWarningLight
        else -> if (isDark) StatusDisabledDark else StatusDisabledLight
    }
    val accentColor = when {
        component.error != null -> if (isDark) ErrorCardIconDark else ErrorCardIconLight
        else -> borderColor
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = component.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            component.error != null -> "错误"
                            component.isRunning -> "运行中"
                            component.isEnabled -> "待命"
                            else -> "未启用"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor
                    )
                }
                Icon(
                    imageVector = when {
                        component.error != null -> Icons.Default.Warning
                        component.isRunning -> Icons.Default.CheckCircle
                        else -> Icons.Default.Close
                    },
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (component.notEnabledReason != null) {
                Text(
                    text = component.notEnabledReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = component.details.lineSequence().take(3).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
