package info.loveyu.mfca

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.ui.ConfigScreen
import info.loveyu.mfca.ui.DrawerMenu
import info.loveyu.mfca.ui.DrawerMenuItem
import info.loveyu.mfca.ui.HelpScreen
import info.loveyu.mfca.ui.LicenseScreen
import info.loveyu.mfca.ui.SettingsScreen
import info.loveyu.mfca.util.AppStatusManager
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.Preferences
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme {
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                var currentScreen by remember { mutableStateOf<Screen?>(null) }
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
                        currentScreen = Screen.Config
                    }
                }

                when (currentScreen) {
                    Screen.Config -> ConfigScreen(onBack = { currentScreen = null })
                    Screen.Help -> HelpScreen(onBack = { currentScreen = null })
                    Screen.Settings -> SettingsScreen(
                        onBack = { currentScreen = null },
                        onOpenLicenses = { currentScreen = Screen.Licenses }
                    )
                    Screen.Licenses -> LicenseScreen(onBack = { currentScreen = Screen.Settings })
                    null -> {
                        ModalNavigationDrawer(
                            drawerState = drawerState,
                            drawerContent = {
                                DrawerMenu(
                                    onItemClick = { item ->
                                        scope.launch { drawerState.close() }
                                        when (item) {
                                            DrawerMenuItem.CONFIG -> currentScreen = Screen.Config
                                            DrawerMenuItem.SAMPLES -> currentScreen = Screen.Help
                                            DrawerMenuItem.SETTINGS -> currentScreen = Screen.Settings
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
                                        currentScreen = Screen.Config
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
    var port by remember { mutableIntStateOf(preferences.port) }
    var receivedCount by remember { mutableIntStateOf(ForwardService.receivedCount) }
    var forwardedCount by remember { mutableIntStateOf(ForwardService.forwardedCount) }
    var isPaused by remember { mutableStateOf(LogManager.isPaused()) }
    var isFileLogging by remember { mutableStateOf(LogManager.isFileLoggingEnabled()) }

    val logs by LogManager.logs.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    ForwardService.onStatsChanged = {
        receivedCount = ForwardService.receivedCount
        forwardedCount = ForwardService.forwardedCount
        isRunning = ForwardService.isRunning
    }

    // Sync initial state from service on first composition
    LaunchedEffect(Unit) {
        isRunning = ForwardService.isRunning
        receivedCount = ForwardService.receivedCount
        forwardedCount = ForwardService.forwardedCount
        port = preferences.port
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

    val localIp = remember {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return@remember addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        "0.0.0.0"
    }

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
                            if (isRunning) {
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
                                text = if (isRunning) stringResource(R.string.status_running)
                                else stringResource(R.string.status_stopped),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isRunning) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isRunning) {
                            Button(onClick = onStopServer) {
                                Text(stringResource(R.string.stop_service))
                            }
                        } else {
                            Button(onClick = onStartServer) {
                                Text(stringResource(R.string.start_service))
                            }
                        }
                    }

                    if (isRunning) {
                        Text(
                            text = "http://$localIp:$port",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatItem(
                            label = stringResource(R.string.messages_received),
                            value = receivedCount
                        )
                        StatItem(
                            label = stringResource(R.string.messages_forwarded),
                            value = forwardedCount
                        )
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
                        Text(
                            text = stringResource(R.string.log_section),
                            style = MaterialTheme.typography.titleMedium
                        )
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
                                Text(
                                    if (isPaused) stringResource(R.string.resume_log)
                                    else stringResource(R.string.pause_log)
                                )
                            }
                            TextButton(
                                onClick = { LogManager.clearLogs() }
                            ) {
                                Text(stringResource(R.string.clear_log))
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
                                Text(
                                    if (isFileLogging) stringResource(R.string.cancel_save_log)
                                    else stringResource(R.string.save_log)
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
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
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
