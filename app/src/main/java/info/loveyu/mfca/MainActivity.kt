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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import info.loveyu.mfca.constants.ApiConstants
import info.loveyu.mfca.server.HttpServer
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.Preferences
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
                MainScreen(
                    onStartService = { startForwardService() },
                    onStopService = { stopForwardService() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (ForwardService.isRunning) {
            ForwardService.refreshNotification()
        }
    }

    private fun startForwardService() {
        val intent = Intent(this, ForwardService::class.java).apply {
            action = ForwardService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopForwardService() {
        val intent = Intent(this, ForwardService::class.java).apply {
            action = ForwardService.ACTION_STOP
        }
        startService(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { Preferences(context) }

    var isRunning by remember { mutableStateOf(ForwardService.isRunning) }
    var port by remember { mutableIntStateOf(preferences.port) }
    var forwardTarget by remember { mutableStateOf(preferences.forwardTarget) }
    var autoStart by remember { mutableStateOf(preferences.autoStart) }
    var receivedCount by remember { mutableIntStateOf(ForwardService.receivedCount) }
    var forwardedCount by remember { mutableIntStateOf(ForwardService.forwardedCount) }

    ForwardService.onStatsChanged = {
        receivedCount = ForwardService.receivedCount
        forwardedCount = ForwardService.forwardedCount
        isRunning = ForwardService.isRunning
    }

    DisposableEffect(Unit) {
        onDispose {
            ForwardService.onStatsChanged = null
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
                title = { Text(context.getString(R.string.app_name)) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Card
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
                        Text(
                            text = if (isRunning) context.getString(R.string.server_running)
                            else context.getString(R.string.server_stopped),
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (isRunning) {
                            Button(onClick = onStopService) {
                                Text(context.getString(R.string.stop_server))
                            }
                        } else {
                            Button(onClick = onStartService) {
                                Text(context.getString(R.string.start_server))
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
                }
            }

            // Stats Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(
                        label = context.getString(R.string.messages_received),
                        value = receivedCount
                    )
                    StatItem(
                        label = context.getString(R.string.messages_forwarded),
                        value = forwardedCount
                    )
                }
            }

            // Settings Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = context.getString(R.string.settings),
                        style = MaterialTheme.typography.titleMedium
                    )

                    // Port
                    OutlinedTextField(
                        value = port.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { p ->
                                port = p
                                preferences.port = p
                            }
                        },
                        label = { Text(context.getString(R.string.port)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRunning,
                        singleLine = true
                    )

                    // Forward Target
                    OutlinedTextField(
                        value = forwardTarget,
                        onValueChange = {
                            forwardTarget = it
                            preferences.forwardTarget = it
                        },
                        label = { Text(context.getString(R.string.forward_target)) },
                        placeholder = { Text(context.getString(R.string.forward_target_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Auto Start
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(context.getString(R.string.settings))
                        Switch(
                            checked = autoStart,
                            onCheckedChange = {
                                autoStart = it
                                preferences.autoStart = it
                            }
                        )
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
