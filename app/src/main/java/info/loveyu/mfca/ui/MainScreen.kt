package info.loveyu.mfca.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.Preferences

private fun getLogColor(log: String): Color {
    return when {
        log.contains("[E:") -> Color(0xFFE53935)
        log.contains("[W:") -> Color(0xFFFFA726)
        log.contains("[I:") -> Color(0xFF43A047)
        log.contains("[D:") -> Color(0xFF42A5F5)
        else -> Color.Unspecified
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preferences = remember { Preferences(context) }

    var isRunning by remember { mutableStateOf(ForwardService.isRunning) }
    var isStarting by remember { mutableStateOf(ForwardService.isStarting) }
    var isPaused by remember { mutableStateOf(LogManager.isPaused()) }
    var isFileLogging by remember { mutableStateOf(LogManager.isFileLoggingEnabled()) }
    var isAllLogcatEnabled by remember { mutableStateOf(LogManager.isAllLogcatEnabled()) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    // Bottom sheet states
    val detailSheetState = rememberModalBottomSheetState()
    var showComponentSheet by remember { mutableStateOf(false) }
    var selectedComponent by remember { mutableStateOf<ComponentStatus?>(null) }

    val logs by LogManager.logs.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    ForwardService.onStatsChanged = {
        isRunning = ForwardService.isRunning
        isStarting = ForwardService.isStarting
    }

    LaunchedEffect(Unit) {
        isRunning = ForwardService.isRunning
        isStarting = ForwardService.isStarting
    }

    DisposableEffect(Unit) {
        onDispose {
            ForwardService.onStatsChanged = null
        }
    }

    LaunchedEffect(Unit) {
        ForwardService.onStartFailed = { errorMsg ->
            Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(isRunning) {
        if (!isRunning) {
            showComponentSheet = false
            selectedComponent = null
        }
    }

    // Network state version for component status refresh
    @Suppress("UNUSED")
    val networkStateVersion by LinkManager.networkStateVersion.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 顶部状态卡片
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                    isRunning -> stringResource(R.string.status_running)
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
            }

            // 组件状态卡片 - 仅在服务运行时显示
            if (isRunning) {
                val (enabledComponents, disabledComponents) = getEnabledAndDisabledComponents(context)
                val totalCount = enabledComponents.size + disabledComponents.size

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showComponentSheet = true }
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.List,
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
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (enabledComponents.isNotEmpty()) {
                                        ComponentCountBadge(
                                            count = enabledComponents.size,
                                            label = "启用",
                                            color = Color(0xFF4CAF50)
                                        )
                                    }
                                    if (disabledComponents.isNotEmpty()) {
                                        ComponentCountBadge(
                                            count = disabledComponents.size,
                                            label = "未启用",
                                            color = Color(0xFF9E9E9E)
                                        )
                                    }
                                }
                            }
                        }

                        if (totalCount > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                enabledComponents.take(5).forEach { component ->
                                    ComponentChip(
                                        component = component,
                                        isEnabled = true,
                                        onClick = {
                                            selectedComponent = component
                                            showComponentSheet = true
                                        }
                                    )
                                }
                                disabledComponents.take(5).forEach { component ->
                                    ComponentChip(
                                        component = component,
                                        isEnabled = false,
                                        onClick = {
                                            selectedComponent = component
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

            // 底部日志卡片 - 占据剩余全部位置
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.log_section),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = LogManager.getLogLevel().name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    if (isPaused) {
                                        LogManager.resumeLogs()
                                    } else {
                                        LogManager.pauseLogs()
                                    }
                                    isPaused = LogManager.isPaused()
                                }
                            ) {
                                Text(if (isPaused) "▶️" else "⏸️")
                            }
                            TextButton(
                                onClick = { LogManager.clearLogs() }
                            ) {
                                Text("🗑️")
                            }
                            TextButton(
                                onClick = {
                                    if (isFileLogging) {
                                        LogManager.setFileLoggingEnabled(false, preferences)
                                        isFileLogging = false
                                        Toast.makeText(context, "已停止保存日志", Toast.LENGTH_SHORT).show()
                                    } else {
                                        LogManager.setFileLoggingEnabled(true, preferences)
                                        isFileLogging = true
                                        Toast.makeText(context, "已开始保存日志到文件", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Text(if (isFileLogging) "❌" else "💾")
                            }
                            TextButton(
                                onClick = {
                                    val newState = !isAllLogcatEnabled
                                    LogManager.logWarn("UI", "Toggle logcat all: $isAllLogcatEnabled -> $newState")
                                    LogManager.setAllLogcatEnabled(newState, preferences)
                                    isAllLogcatEnabled = LogManager.isAllLogcatEnabled()
                                }
                            ) {
                                Text(
                                    if (isAllLogcatEnabled) "🐱" else "🐾"
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        state = listState
                    ) {
                        items(logs) { log ->
                            Text(
                                text = log.chunked(1).joinToString("\u200B"),
                                style = MaterialTheme.typography.bodySmall,
                                color = getLogColor(log),
                                softWrap = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("log", log))
                                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                    }
                            )
                        }
                    }
                }
            }
        }
    }

    // Component status bottom sheet
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
fun ComponentCountBadge(
    count: Int,
    label: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .background(
                color = color.copy(alpha = 0.1f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
fun ComponentChip(
    component: ComponentStatus,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isEnabled) {
        when (component.type) {
            ComponentType.LINK -> Color(0xFFE3F2FD)
            ComponentType.HTTP_INPUT -> Color(0xFFE8F5E9)
            ComponentType.LINK_INPUT -> Color(0xFFFFF3E0)
        }
    } else {
        Color(0xFFF5F5F5)
    }

    @Suppress("UNUSED")
    val borderColor = if (isEnabled) {
        when (component.type) {
            ComponentType.LINK -> Color(0xFF1976D2)
            ComponentType.HTTP_INPUT -> Color(0xFF388E3C)
            ComponentType.LINK_INPUT -> Color(0xFFF57C00)
        }
    } else {
        Color(0xFFBDBDBD)
    }

    val textColor = if (isEnabled) {
        when (component.type) {
            ComponentType.LINK -> Color(0xFF1976D2)
            ComponentType.HTTP_INPUT -> Color(0xFF388E3C)
            ComponentType.LINK_INPUT -> Color(0xFFF57C00)
        }
    } else {
        Color(0xFF757575)
    }

    Card(
        modifier = Modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        color = if (component.isRunning) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
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
