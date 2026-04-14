package info.loveyu.mfca

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.ui.ComponentDetailSheet
import info.loveyu.mfca.ui.ComponentStatus
import info.loveyu.mfca.ui.ComponentType
import info.loveyu.mfca.ui.DrawerMenu
import info.loveyu.mfca.ui.DrawerMenuItem
import info.loveyu.mfca.ui.getEnabledAndDisabledComponents
import info.loveyu.mfca.ui.theme.MfcaTheme
import info.loveyu.mfca.util.AppStatusManager
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.Preferences
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    private val wifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Trigger network state refresh after permission result
        if (ForwardService.isServiceAlive()) {
            LinkManager.refreshNetworkState()
        }
        // 精确定位授权后，请求后台定位权限（Android 10+ 需要分步请求）
        requestBackgroundLocationIfNeeded()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (ForwardService.isServiceAlive()) {
            LinkManager.refreshNetworkState()
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android 11+ 请求所有文件访问权限（sdcard:// 路径需要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                wifiPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.NEARBY_WIFI_DEVICES,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
            }
        }

        setContent {
            MfcaTheme {
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val preferences = remember { Preferences(this@MainActivity) }

                // Check config existence on startup
                LaunchedEffect(Unit) {
                    LogManager.init(this@MainActivity, preferences)
                    if (!ForwardService.isServiceAlive() && !preferences.hasConfig()) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.config_not_found,
                            Toast.LENGTH_LONG
                        ).show()
                        // Launch ConfigActivity directly if no config found
                        startActivity(Intent(this@MainActivity, ConfigActivity::class.java))
                    }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        DrawerMenu(
                            onItemClick = { item ->
                                scope.launch { drawerState.close() }
                                when (item) {
                                    DrawerMenuItem.CONFIG -> {
                                        startActivity(Intent(this@MainActivity, ConfigActivity::class.java))
                                    }
                                    DrawerMenuItem.SAMPLES -> {
                                        startActivity(Intent(this@MainActivity, HelpActivity::class.java))
                                    }
                                    DrawerMenuItem.SETTINGS -> {
                                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                                    }
                                }
                            }
                        )
                    }
                ) {
                    MainScreen(
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onStartServer = {
                            if (!preferences.hasConfig()) {
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.config_not_found,
                                    Toast.LENGTH_LONG
                                ).show()
                                startActivity(Intent(this@MainActivity, ConfigActivity::class.java))
                            } else {
                                startServer()
                            }
                        },
                        onStopServer = { stopServer() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ensureServiceRunning()
    }

    private fun ensureServiceRunning() {
        if (!ForwardService.isServiceAlive()) {
            val status = AppStatusManager.loadStatus(this)
            val intent = Intent(this, ForwardService::class.java).apply {
                action = if (status.isRunning) ForwardService.ACTION_START else ForwardService.ACTION_INIT
            }
            startForegroundService(intent)
        } else {
            ForwardService.refreshNotification()
        }
    }

    private fun startServer() {
        val intent = Intent(this, ForwardService::class.java).apply {
            action = ForwardService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopServer() {
        val intent = Intent(this, ForwardService::class.java).apply {
            action = ForwardService.ACTION_STOP
        }
        startService(intent)
    }
}

private enum class Screen {
    Config, Help, Settings, Licenses
}

private fun getLogColor(log: String): Color {
    return when {
        log.contains("[E:") -> Color(0xFFE53935) // ERROR - Red
        log.contains("[W:") -> Color(0xFFFFA726) // WARN - Orange
        log.contains("[I:") -> Color(0xFF43A047) // INFO - Green
        log.contains("[D:") -> Color(0xFF42A5F5) // DEBUG - Blue
        else -> Color.Unspecified
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenDrawer: () -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { Preferences(context) }

    var isRunning by remember { mutableStateOf(ForwardService.isRunning) }
    var isStarting by remember { mutableStateOf(ForwardService.isStarting) }
    var isPaused by remember { mutableStateOf(LogManager.isPaused()) }
    var isFileLogging by remember { mutableStateOf(LogManager.isFileLoggingEnabled()) }
    var isAllLogcatEnabled by remember { mutableStateOf(LogManager.isAllLogcatEnabled()) }

    // Bottom sheet states
    val componentSheetState = rememberModalBottomSheetState()
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

    // Sync initial state from service on first composition
    LaunchedEffect(Unit) {
        isRunning = ForwardService.isRunning
        isStarting = ForwardService.isStarting
    }

    DisposableEffect(Unit) {
        onDispose {
            ForwardService.onStatsChanged = null
        }
    }

    // Listen for start failures - runs on main thread
    LaunchedEffect(Unit) {
        ForwardService.onStartFailed = { errorMsg ->
            Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
        }
    }

    // Close component sheet when service stops
    LaunchedEffect(isRunning) {
        if (!isRunning) {
            showComponentSheet = false
            selectedComponent = null
        }
    }

    // Network state version for component status refresh
    val networkStateVersion by LinkManager.networkStateVersion.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "菜单"
                        )
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

                        // Horizontal scroll of component chips
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
fun StatItem(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall
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
