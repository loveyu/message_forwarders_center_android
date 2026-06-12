package info.loveyu.mfca.m2m

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    val store = remember { M2mStateStore(context) }
    var mode by remember { mutableStateOf(initialMode) }
    var selectedPackages by remember { mutableStateOf(initialPackages.toSet()) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(store.getShowSystemApps()) }
    var sortMode by remember { mutableStateOf(store.getAppSortMode()) }
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(showSystemApps) {
        store.setShowSystemApps(showSystemApps)
        isLoading = true
        apps = withContext(Dispatchers.IO) { loadApps(context, showSystemApps) }
        isLoading = false
    }

    LaunchedEffect(sortMode) {
        store.setAppSortMode(sortMode)
    }

    val displayApps by remember {
        derivedStateOf {
            when (sortMode) {
                "selected_first" -> apps.sortedByDescending { it.packageName in selectedPackages }
                "unselected_first" -> apps.sortedBy { it.packageName in selectedPackages }
                else -> apps
            }
        }
    }

    val filteredApps =
        remember(displayApps, searchQuery) {
            val q = searchQuery.trim()
            if (q.isBlank()) displayApps
            else displayApps.filter { it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
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
                    AppSelectDropdownMenu(
                        expanded = menuExpanded,
                        sortMode = sortMode,
                        onSelectAll = { selectedPackages = filteredApps.map { it.packageName }.toSet() },
                        onSelectNone = { selectedPackages = emptySet() },
                        onInvertSelection = {
                            val allVisible = filteredApps.map { it.packageName }.toSet()
                            selectedPackages = (allVisible - selectedPackages) + (selectedPackages - allVisible)
                        },
                        onImportClipboard = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val text = cb?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            val imported = text.lines().map { it.trim() }.filter { it.isNotBlank() }
                            selectedPackages = selectedPackages + imported
                            Toast.makeText(
                                context,
                                context.getString(R.string.vpn_imported_packages, imported.size),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        onExportClipboard = {
                            val text = selectedPackages.sorted().joinToString("\n")
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cb?.setPrimaryClip(ClipData.newPlainText("packages", text))
                            Toast.makeText(
                                context,
                                context.getString(R.string.vpn_exported_packages, selectedPackages.size),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        onToggleSortMode = { sortMode = if (sortMode == "selected_first") null else "selected_first" },
                        onDismissRequest = { menuExpanded = false },
                    )
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
                        AppSelectRow(
                            app = app,
                            isSelected = app.packageName in selectedPackages,
                            onToggle = { checked ->
                                selectedPackages = if (checked) {
                                    selectedPackages + app.packageName
                                } else {
                                    selectedPackages - app.packageName
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
