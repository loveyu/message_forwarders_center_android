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
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.ui.ClipboardHistoryContent
import info.loveyu.mfca.ui.ClipboardHistoryTopBar
import info.loveyu.mfca.ui.MainScreen
import info.loveyu.mfca.ui.MainTopBar
import info.loveyu.mfca.ui.NotifyHistoryContent
import info.loveyu.mfca.ui.NotifyHistoryTopBar
import info.loveyu.mfca.ui.theme.MfcaTheme
import info.loveyu.mfca.util.AppStatusManager
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.Preferences
import kotlinx.coroutines.launch

enum class BottomTab(
    val icon: ImageVector,
    val labelResId: Int
) {
    HOME(Icons.Default.Home, R.string.tab_home),
    NOTIFY_HISTORY(Icons.Default.Notifications, R.string.tab_notify_history),
    CLIPBOARD_HISTORY(Icons.Default.ContentPaste, R.string.tab_clipboard_history)
}

class MainActivity : ComponentActivity() {
    private val pendingNotifyId = mutableIntStateOf(-1)
    private val pendingHighlight = mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
            } catch (_: Exception) {
            }
        }
    }

    private val wifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (ForwardService.isServiceAlive()) LinkManager.refreshNetworkState()
        requestBackgroundLocationIfNeeded()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (ForwardService.isServiceAlive()) LinkManager.refreshNetworkState()
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
                != PackageManager.PERMISSION_GRANTED
            ) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

        handleIntent(intent)

        setContent {
            MfcaTheme {
                MainContent(
                    activity = this@MainActivity,
                    pendingNotifyId = pendingNotifyId,
                    pendingHighlight = pendingHighlight
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ensureServiceRunning()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val notifyId = intent?.getIntExtra("notify_id", -1) ?: -1
        val highlight = intent?.getBooleanExtra("highlight", false) ?: false
        if (highlight && notifyId != -1) {
            pendingNotifyId.intValue = notifyId
            pendingHighlight.value = true
        }
    }

    private fun ensureServiceRunning() {
        if (!ForwardService.isServiceAlive()) {
            val status = AppStatusManager.loadStatus(this)
            val intent = Intent(this, ForwardService::class.java).apply {
                action =
                    if (status.isRunning) ForwardService.ACTION_START else ForwardService.ACTION_INIT
            }
            startForegroundService(intent)
        } else {
            ForwardService.refreshNotification()
        }
    }

    internal fun startServer() {
        startForegroundService(Intent(this, ForwardService::class.java).apply {
            action = ForwardService.ACTION_START
        })
    }

    internal fun stopServer() {
        startService(Intent(this, ForwardService::class.java).apply {
            action = ForwardService.ACTION_STOP
        })
    }
}

@Composable
private fun MainContent(
    activity: MainActivity,
    pendingNotifyId: androidx.compose.runtime.MutableIntState,
    pendingHighlight: androidx.compose.runtime.MutableState<Boolean>
) {
    val preferences = remember { Preferences(activity) }
    var selectedTab by remember { mutableStateOf(BottomTab.HOME) }
    var highlightNotifyId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(pendingHighlight.value) {
        if (pendingHighlight.value && pendingNotifyId.intValue != -1) {
            highlightNotifyId = pendingNotifyId.intValue
            selectedTab = BottomTab.NOTIFY_HISTORY
            pendingHighlight.value = false
        }
    }

    LaunchedEffect(Unit) {
        LogManager.init(activity, preferences)
        if (!ForwardService.isServiceAlive() && !preferences.hasConfig()) {
            Toast.makeText(activity, R.string.config_not_found, Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(activity, ConfigActivity::class.java))
        }
    }

    var showTabLabel by remember { mutableStateOf(preferences.showTabLabel) }

    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        showTabLabel = preferences.showTabLabel
        onPauseOrDispose { }
    }
    val notifyDrawerState =
        remember { androidx.compose.material3.DrawerState(androidx.compose.material3.DrawerValue.Closed) }
    val notifyScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            when (selectedTab) {
                BottomTab.HOME -> MainTopBar()
                BottomTab.NOTIFY_HISTORY -> NotifyHistoryTopBar(
                    onMenuClick = {
                        notifyScope.launch { notifyDrawerState.open() }
                    }
                )
                BottomTab.CLIPBOARD_HISTORY -> ClipboardHistoryTopBar()
            }
        },
        bottomBar = {
            NavigationBar(
                modifier = if (!showTabLabel) Modifier.height(96.dp) else Modifier,
            ) {
                BottomTab.entries.forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = if (showTabLabel) {
                            { Text(stringResource(tab.labelResId)) }
                        } else null,
                        selected = selectedTab == tab,
                        onClick = {
                            selectedTab = tab
                            if (tab != BottomTab.NOTIFY_HISTORY) highlightNotifyId = null
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            BottomTab.HOME -> MainScreen(
                onStartServer = {
                    if (!preferences.hasConfig()) {
                        Toast.makeText(activity, R.string.config_not_found, Toast.LENGTH_LONG)
                            .show()
                        activity.startActivity(Intent(activity, ConfigActivity::class.java))
                    } else {
                        activity.startServer()
                    }
                },
                onStopServer = { activity.stopServer() },
                contentPadding = innerPadding
            )

            BottomTab.NOTIFY_HISTORY -> NotifyHistoryContent(
                onBack = { selectedTab = BottomTab.HOME },
                highlightNotifyId = highlightNotifyId,
                drawerState = notifyDrawerState,
                contentPadding = innerPadding
            )

            BottomTab.CLIPBOARD_HISTORY -> ClipboardHistoryContent(
                contentPadding = innerPadding
            )
        }
    }
}
