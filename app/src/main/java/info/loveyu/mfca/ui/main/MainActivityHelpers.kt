package info.loveyu.mfca.ui.main

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.ui.main.MainActivity
import info.loveyu.mfca.R
import info.loveyu.mfca.service.ForwardService
import info.loveyu.mfca.util.AppStatusManager
import info.loveyu.mfca.util.Preferences

enum class BottomTab(
    val icon: ImageVector,
    val labelResId: Int
) {
    HOME(Icons.Default.Home, R.string.tab_home),
    NOTIFY_HISTORY(Icons.Default.Notifications, R.string.tab_notify_history),
    CLIPBOARD_HISTORY(Icons.Default.ContentPaste, R.string.tab_clipboard_history),
    VPN(Icons.Default.Security, R.string.tab_vpn),
}

fun MainActivity.ensureServiceRunning() {
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

fun MainActivity.promptBatteryOptimization() {
    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    if (pm.isIgnoringBatteryOptimizations(packageName)) return
    val prefs = Preferences(this)
    if (prefs.batteryOptPrompted) return
    prefs.batteryOptPrompted = true
    try {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:$packageName")
        })
    } catch (_: Exception) {
    }
}

@Composable
fun MainBottomBar(
    tabs: List<BottomTab>,
    selectedTab: BottomTab,
    showTabLabel: Boolean,
    onTabSelected: (BottomTab) -> Unit,
) {
    NavigationBar(
        modifier = if (!showTabLabel) Modifier.height(96.dp) else Modifier,
    ) {
        tabs.forEach { tab ->
            NavigationBarItem(
                icon = { Icon(tab.icon, contentDescription = null) },
                label = if (showTabLabel) { { Text(stringResource(tab.labelResId)) } } else null,
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
            )
        }
    }
}
