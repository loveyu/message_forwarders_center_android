@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.R
import info.loveyu.mfca.m2m.M2mTrafficStats

@Composable
fun M2mTrafficCard(trafficStats: M2mTrafficStats?) {
    ElevatedCard {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
        ) {
            Text(
                text = stringResource(R.string.vpn_traffic_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "↓ ${formatSpeed(trafficStats?.rxSpeed ?: 0L)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.vpn_download_speed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "↑ ${formatSpeed(trafficStats?.txSpeed ?: 0L)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        text = stringResource(R.string.vpn_upload_speed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        R.string.vpn_total_download,
                        formatBytes(trafficStats?.totalRxBytes ?: 0L)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.vpn_total_upload,
                        formatBytes(trafficStats?.totalTxBytes ?: 0L)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

fun runtimeStatusLabel(status: info.loveyu.mfca.m2m.M2mRuntimeStatus): String = when (status) {
    info.loveyu.mfca.m2m.M2mRuntimeStatus.disabled -> "Disabled"
    info.loveyu.mfca.m2m.M2mRuntimeStatus.idle -> "Idle"
    info.loveyu.mfca.m2m.M2mRuntimeStatus.preparing -> "Preparing"
    info.loveyu.mfca.m2m.M2mRuntimeStatus.prepared -> "Prepared"
    info.loveyu.mfca.m2m.M2mRuntimeStatus.starting -> "Starting"
    info.loveyu.mfca.m2m.M2mRuntimeStatus.running -> "Running"
    info.loveyu.mfca.m2m.M2mRuntimeStatus.stopping -> "Stopping"
    info.loveyu.mfca.m2m.M2mRuntimeStatus.error -> "Error"
}

fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec < 1024 -> "$bytesPerSec B/s"
        bytesPerSec < 1024 * 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
        bytesPerSec < 1024 * 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024))
        else -> String.format("%.1f GB/s", bytesPerSec / (1024.0 * 1024 * 1024))
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
