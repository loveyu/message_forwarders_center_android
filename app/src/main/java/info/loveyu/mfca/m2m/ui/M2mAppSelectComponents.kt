package info.loveyu.mfca.m2m.ui

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import info.loveyu.mfca.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppItem(
    val label: String,
    val packageName: String,
)

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    val drawable: Drawable = context.packageManager.getApplicationIcon(packageName)
                    drawable.toBitmap().asImageBitmap()
                }.getOrNull()
            }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (icon != null) {
            Image(bitmap = icon!!, contentDescription = null, modifier = Modifier.size(36.dp))
        } else {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

fun loadApps(context: Context, includeSystem: Boolean): List<AppItem> {
    val pm = context.packageManager
    val packages =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }
    return packages
        .filter { pkg ->
            val app = pkg.applicationInfo ?: return@filter false
            if (!includeSystem && (app.flags and ApplicationInfo.FLAG_SYSTEM != 0)) return@filter false
            pkg.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
        }
        .map { pkg ->
            AppItem(
                label = pm.getApplicationLabel(pkg.applicationInfo!!).toString(),
                packageName = pkg.packageName,
            )
        }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun AppSelectDropdownMenu(
    expanded: Boolean,
    sortMode: String?,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onInvertSelection: () -> Unit,
    onImportClipboard: () -> Unit,
    onExportClipboard: () -> Unit,
    onToggleSortMode: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.vpn_select_all)) },
            onClick = { onSelectAll(); onDismissRequest() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.vpn_select_none)) },
            onClick = { onSelectNone(); onDismissRequest() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.vpn_select_invert)) },
            onClick = { onInvertSelection(); onDismissRequest() },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.vpn_import_clipboard)) },
            onClick = { onImportClipboard(); onDismissRequest() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.vpn_export_clipboard)) },
            onClick = { onExportClipboard(); onDismissRequest() },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (sortMode == "selected_first") R.string.vpn_sort_unselected_first
                        else R.string.vpn_sort_selected_first
                    )
                )
            },
            onClick = { onToggleSortMode(); onDismissRequest() },
        )
    }
}

@Composable
fun AppSelectRow(
    app: AppItem,
    isSelected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        AppIcon(packageName = app.packageName, modifier = Modifier.size(40.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = isSelected, onCheckedChange = onToggle)
    }
}
