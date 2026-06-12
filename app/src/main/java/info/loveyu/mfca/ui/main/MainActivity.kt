package info.loveyu.mfca.ui.main

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import info.loveyu.mfca.link.LinkManager
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.ui.MainScreen
import info.loveyu.mfca.ui.MainTopBar
import info.loveyu.mfca.ui.M2mScreen
import info.loveyu.mfca.ui.clipboard.ClipboardHistoryContent
import info.loveyu.mfca.ui.clipboard.ClipboardHistoryTopBar
import info.loveyu.mfca.ui.notification.NotifyHistoryContent
import info.loveyu.mfca.ui.notification.NotifyHistoryTopBar
import info.loveyu.mfca.ui.theme.MfcaTheme
import info.loveyu.mfca.R
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.Preferences
import info.loveyu.mfca.ui.config.ConfigActivity
import info.loveyu.mfca.m2m.core.M2mManager
import info.loveyu.mfca.m2m.models.M2mUiState
import info.loveyu.mfca.ui.main.BottomTab
import info.loveyu.mfca.ui.main.MainBottomBar
import info.loveyu.mfca.ui.main.ensureServiceRunning
import info.loveyu.mfca.ui.main.promptBatteryOptimization
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    val pendingNotifyId = mutableIntStateOf(-1)
    val pendingHighlight = mutableStateOf(false)

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

        handleIntentInternal(intent)

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
        promptBatteryOptimization()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentInternal(intent)
    }

    private fun handleIntentInternal(intent: Intent?) {
        val notifyId = intent?.getIntExtra("notify_id", -1) ?: -1
        val highlight = intent?.getBooleanExtra("highlight", false) ?: false
        if (highlight && notifyId != -1) {
            pendingNotifyId.intValue = notifyId
            pendingHighlight.value = true
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
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var lastTabClickTime by remember { mutableStateOf(0L) }
    val vpnUiState by M2mManager.state.collectAsState()
    val tabs = remember(vpnUiState.hasVpnConfig) {
        buildList {
            add(BottomTab.HOME)
            add(BottomTab.NOTIFY_HISTORY)
            add(BottomTab.CLIPBOARD_HISTORY)
            if (vpnUiState.hasVpnConfig) {
                add(BottomTab.VPN)
            }
        }
    }

    LaunchedEffect(pendingHighlight.value) {
        if (pendingHighlight.value && pendingNotifyId.intValue != -1) {
            highlightNotifyId = pendingNotifyId.intValue
            selectedTab = BottomTab.NOTIFY_HISTORY
            pendingHighlight.value = false
        }
    }

    LaunchedEffect(tabs, selectedTab) {
        if (selectedTab !in tabs) {
            selectedTab = BottomTab.HOME
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

    var cleanByTimeTrigger by remember { mutableIntStateOf(0) }
    var cleanPasswordsTrigger by remember { mutableIntStateOf(0) }
    var cleanVerificationCodesTrigger by remember { mutableIntStateOf(0) }

    fun handleTabClick(tab: BottomTab) {
        if (selectedTab == tab) {
            val now = System.currentTimeMillis()
            if (now - lastTabClickTime < 500) {
                refreshTrigger++
                lastTabClickTime = 0L
            } else {
                lastTabClickTime = now
            }
        }
        selectedTab = tab
        if (tab != BottomTab.NOTIFY_HISTORY) highlightNotifyId = null
    }

    androidx.compose.material3.Scaffold(
        topBar = {
            when (selectedTab) {
                BottomTab.HOME -> MainTopBar()
                BottomTab.NOTIFY_HISTORY -> NotifyHistoryTopBar(
                    onMenuClick = { notifyScope.launch { notifyDrawerState.open() } }
                )
                BottomTab.CLIPBOARD_HISTORY -> ClipboardHistoryTopBar(
                    onCleanByTime = { cleanByTimeTrigger++ },
                    onCleanPasswords = { cleanPasswordsTrigger++ },
                    onCleanVerificationCodes = { cleanVerificationCodesTrigger++ }
                )
                BottomTab.VPN -> { }
            }
        },
        bottomBar = {
            MainBottomBar(
                tabs = tabs,
                selectedTab = selectedTab,
                showTabLabel = showTabLabel,
                onTabSelected = { handleTabClick(it) },
            )
        }
    ) { innerPadding ->
        when (selectedTab) {
            BottomTab.HOME -> MainScreen(
                onStartServer = {
                    if (!preferences.hasConfig()) {
                        Toast.makeText(activity, R.string.config_not_found, Toast.LENGTH_LONG).show()
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
                contentPadding = innerPadding,
                refreshTrigger = refreshTrigger
            )

            BottomTab.CLIPBOARD_HISTORY -> ClipboardHistoryContent(
                contentPadding = innerPadding,
                refreshTrigger = refreshTrigger,
                cleanByTimeTrigger = cleanByTimeTrigger,
                cleanPasswordsTrigger = cleanPasswordsTrigger,
                cleanVerificationCodesTrigger = cleanVerificationCodesTrigger,
                onCleanTriggerConsumed = {
                    cleanByTimeTrigger = 0
                    cleanPasswordsTrigger = 0
                    cleanVerificationCodesTrigger = 0
                }
            )

            BottomTab.VPN -> M2mScreen(contentPadding = innerPadding)
        }
    }
}
