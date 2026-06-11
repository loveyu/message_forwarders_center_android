package info.loveyu.mfca.m2m

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import info.loveyu.mfca.R
import info.loveyu.mfca.config.M2mAccessControlMode
import info.loveyu.mfca.ui.theme.MfcaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class M2mAppSelectActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_CANDIDATE_NAME = "candidate_name"

        fun intent(context: Context, candidateName: String): Intent =
            Intent(context, M2mAppSelectActivity::class.java).putExtra(EXTRA_CANDIDATE_NAME, candidateName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val candidateName = intent.getStringExtra(EXTRA_CANDIDATE_NAME) ?: run { finish(); return }
        val store = M2mStateStore(this)
        val candidate = M2mManager.state.value.candidates.firstOrNull { it.config.name == candidateName }
        val initialMode = store.getAccessControlMode(candidateName, candidate?.config?.accessControlMode ?: M2mAccessControlMode.acceptAll)
        val initialPackages = store.getPackages(candidateName, candidate?.config?.packages ?: emptyList())

        setContent {
            MfcaTheme {
                M2mAppSelectScreen(
                    candidateName = candidateName,
                    initialMode = initialMode,
                    initialPackages = initialPackages,
                    onSave = { mode, packages ->
                        M2mManager.updateAccessControl(candidateName, mode, packages)
                        finish()
                    },
                    onBack = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun M2mAppSelectScreen(
    candidateName: String,
    initialMode: M2mAccessControlMode,
    initialPackages: List<String>,
    onSave: (M2mAccessControlMode, List<String>) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(initialMode) }
    var selectedPackages by remember { mutableStateOf(initialPackages.toMutableSet()) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(showSystemApps) {
        isLoading = true
        apps = withContext(Dispatchers.IO) { loadApps(context, showSystemApps) }
        isLoading = false
    }

    val filteredApps =
        remember(apps, searchQuery) {
            val q = searchQuery.trim()
            if (q.isBlank()) apps
            else apps.filter { it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
        }

    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(candidateName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vpn_select_all)) },
                            onClick = {
                                selectedPackages = filteredApps.map { it.packageName }.toMutableSet()
                                menuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vpn_select_none)) },
                            onClick = {
                                selectedPackages = mutableSetOf()
                                menuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vpn_select_invert)) },
                            onClick = {
                                val allVisible = filteredApps.map { it.packageName }.toSet()
                                selectedPackages =
                                    (allVisible - selectedPackages.toSet() + (selectedPackages - allVisible))
                                        .toMutableSet()
                                menuExpanded = false
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vpn_import_clipboard)) },
                            onClick = {
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val text = cb?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                val imported = text.lines().map { it.trim() }.filter { it.isNotBlank() }
                                selectedPackages = imported.toMutableSet()
                                menuExpanded = false
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.vpn_imported_packages, imported.size),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vpn_export_clipboard)) },
                            onClick = {
                                val text = selectedPackages.sorted().joinToString("\n")
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cb?.setPrimaryClip(ClipData.newPlainText("packages", text))
                                menuExpanded = false
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.vpn_exported_packages, selectedPackages.size),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    }
                    TextButton(onClick = { onSave(mode, selectedPackages.toList().sorted()) }) {
                        Text(stringResource(R.string.vpn_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                M2mAccessControlMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(m.name) },
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.vpn_show_system_apps),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(checked = showSystemApps, onCheckedChange = { showSystemApps = it })
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(stringResource(R.string.vpn_search_apps)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon =
                    if (searchQuery.isNotEmpty()) {
                        { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = null) } }
                    } else {
                        null
                    },
            )

            Text(
                text = stringResource(R.string.vpn_selected_count, selectedPackages.size, filteredApps.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
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
                            Checkbox(
                                checked = app.packageName in selectedPackages,
                                onCheckedChange = { checked ->
                                    selectedPackages =
                                        selectedPackages.toMutableSet().apply {
                                            if (checked) add(app.packageName) else remove(app.packageName)
                                        }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class AppItem(
    val label: String,
    val packageName: String,
)

/** Lazily loads and displays an app icon. Triggered only when the item enters the composition (i.e. scrolls into view). */
@Composable
private fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
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

private fun loadApps(context: Context, includeSystem: Boolean): List<AppItem> {
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
