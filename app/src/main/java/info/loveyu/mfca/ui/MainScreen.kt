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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.ConfigActivity
import info.loveyu.mfca.HelpActivity
import info.loveyu.mfca.R
import info.loveyu.mfca.SettingsActivity
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.ui.theme.BadgeDisabledDark
import info.loveyu.mfca.ui.theme.BadgeDisabledLight
import info.loveyu.mfca.ui.theme.BadgeEnabledDark
import info.loveyu.mfca.ui.theme.BadgeEnabledLight
import info.loveyu.mfca.ui.theme.DisabledChipBgDark
import info.loveyu.mfca.ui.theme.DisabledChipBgLight
import info.loveyu.mfca.ui.theme.DisabledChipBorderDark
import info.loveyu.mfca.ui.theme.DisabledChipBorderLight
import info.loveyu.mfca.ui.theme.DisabledChipTextDark
import info.loveyu.mfca.ui.theme.DisabledChipTextLight
import info.loveyu.mfca.ui.theme.HttpInputChipBgDark
import info.loveyu.mfca.ui.theme.HttpInputChipBgLight
import info.loveyu.mfca.ui.theme.HttpInputChipBorderDark
import info.loveyu.mfca.ui.theme.HttpInputChipBorderLight
import info.loveyu.mfca.ui.theme.HttpInputChipTextDark
import info.loveyu.mfca.ui.theme.HttpInputChipTextLight
import info.loveyu.mfca.ui.theme.LinkChipBgDark
import info.loveyu.mfca.ui.theme.LinkChipBgLight
import info.loveyu.mfca.ui.theme.LinkChipBorderDark
import info.loveyu.mfca.ui.theme.LinkChipBorderLight
import info.loveyu.mfca.ui.theme.LinkChipTextDark
import info.loveyu.mfca.ui.theme.LinkChipTextLight
import info.loveyu.mfca.ui.theme.LinkInputChipBgDark
import info.loveyu.mfca.ui.theme.LinkInputChipBgLight
import info.loveyu.mfca.ui.theme.LinkInputChipBorderDark
import info.loveyu.mfca.ui.theme.LinkInputChipBorderLight
import info.loveyu.mfca.ui.theme.LinkInputChipTextDark
import info.loveyu.mfca.ui.theme.LinkInputChipTextLight
import info.loveyu.mfca.ui.theme.LogDebugColor
import info.loveyu.mfca.ui.theme.LogErrorColor
import info.loveyu.mfca.ui.theme.LogInfoColor
import info.loveyu.mfca.ui.theme.LogWarnColor
import info.loveyu.mfca.ui.theme.StatusDisabledDark
import info.loveyu.mfca.ui.theme.StatusDisabledLight
import info.loveyu.mfca.ui.theme.StatusRunningDark
import info.loveyu.mfca.ui.theme.StatusRunningLight
import info.loveyu.mfca.util.LogLevel
import info.loveyu.mfca.util.Preferences

/** LogLevel 到 Compose Color 的映射（替代原来的字符串解析） */
private fun LogLevel.toColor(): Color = when (this) {
    LogLevel.ERROR -> LogErrorColor
    LogLevel.WARN -> LogWarnColor
    LogLevel.INFO -> LogInfoColor
    LogLevel.DEBUG -> LogDebugColor
}

@Composable
fun MainTopBar() {
    val context = LocalContext.current
    var showOverflowMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "菜单"
                    )
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.config_management)) },
                        leadingIcon = { Icon(Icons.Default.Build, contentDescription = null) },
                        onClick = {
                            showOverflowMenu = false
                            context.startActivity(Intent(context, ConfigActivity::class.java))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sample_configs)) },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                        onClick = {
                            showOverflowMenu = false
                            context.startActivity(Intent(context, HelpActivity::class.java))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.system_settings)) },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        onClick = {
                            showOverflowMenu = false
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        }
                    )
                }
            }
        }
    )
}

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

    val detailSheetState = rememberModalBottomSheetState()
    var showComponentSheet by remember { mutableStateOf(false) }
    var selectedComponent by remember { mutableStateOf<ComponentStatus?>(null) }

    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        isRunning = ForwardService.isRunning
        isStarting = ForwardService.isStarting
        ForwardService.onStatsChanged = {
            isRunning = ForwardService.isRunning
            isStarting = ForwardService.isStarting
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
            selectedComponent = null
        }
    }

    @Suppress("UNUSED")
    val networkStateVersion by LinkManager.networkStateVersion.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 顶部状态卡片
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
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

        // 组件状态卡片
        if (isRunning) {
            val (enabledComponents, disabledComponents) = getEnabledAndDisabledComponents(context)
            val totalCount = enabledComponents.size + disabledComponents.size

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showComponentSheet = true }
            ) {
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
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        if (totalCount > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (enabledComponents.isNotEmpty()) {
                                    ComponentCountBadge(count = enabledComponents.size, label = "启用", isEnabled = true)
                                }
                                if (disabledComponents.isNotEmpty()) {
                                    ComponentCountBadge(count = disabledComponents.size, label = "未启用", isEnabled = false)
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
                                ComponentChip(component = component, isEnabled = true, onClick = {
                                    selectedComponent = component
                                    showComponentSheet = true
                                })
                            }
                            disabledComponents.take(5).forEach { component ->
                                ComponentChip(component = component, isEnabled = false, onClick = {
                                    selectedComponent = component
                                    showComponentSheet = true
                                })
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

        // 日志卡片
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
                selectedComponent = null
            }
        )
    }
}

@Composable
fun ComponentCountBadge(count: Int, label: String, isEnabled: Boolean) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val color = if (isEnabled) {
        if (isDark) BadgeEnabledDark else BadgeEnabledLight
    } else {
        if (isDark) BadgeDisabledDark else BadgeDisabledLight
    }
    Row(
        modifier = Modifier
            .background(color = color.copy(alpha = 0.1f), shape = RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "$count", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun ComponentChip(component: ComponentStatus, isEnabled: Boolean, onClick: () -> Unit) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val backgroundColor = if (isEnabled) {
        when (component.type) {
            ComponentType.LINK -> if (isDark) LinkChipBgDark else LinkChipBgLight
            ComponentType.HTTP_INPUT -> if (isDark) HttpInputChipBgDark else HttpInputChipBgLight
            ComponentType.LINK_INPUT -> if (isDark) LinkInputChipBgDark else LinkInputChipBgLight
        }
    } else {
        if (isDark) DisabledChipBgDark else DisabledChipBgLight
    }

    @Suppress("UNUSED")
    val borderColor = if (isEnabled) {
        when (component.type) {
            ComponentType.LINK -> if (isDark) LinkChipBorderDark else LinkChipBorderLight
            ComponentType.HTTP_INPUT -> if (isDark) HttpInputChipBorderDark else HttpInputChipBorderLight
            ComponentType.LINK_INPUT -> if (isDark) LinkInputChipBorderDark else LinkInputChipBorderLight
        }
    } else {
        if (isDark) DisabledChipBorderDark else DisabledChipBorderLight
    }

    val textColor = if (isEnabled) {
        when (component.type) {
            ComponentType.LINK -> if (isDark) LinkChipTextDark else LinkChipTextLight
            ComponentType.HTTP_INPUT -> if (isDark) HttpInputChipTextDark else HttpInputChipTextLight
            ComponentType.LINK_INPUT -> if (isDark) LinkInputChipTextDark else LinkInputChipTextLight
        }
    } else {
        if (isDark) DisabledChipTextDark else DisabledChipTextLight
    }

    val statusColor = if (component.isRunning) {
        if (isDark) StatusRunningDark else StatusRunningLight
    } else {
        if (isDark) StatusDisabledDark else StatusDisabledLight
    }

    Card(
        modifier = Modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        color = statusColor,
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = component.name,
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
