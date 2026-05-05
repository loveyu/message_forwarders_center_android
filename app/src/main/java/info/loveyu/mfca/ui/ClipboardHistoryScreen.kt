@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import info.loveyu.mfca.ClipboardDetailActivity
import info.loveyu.mfca.ClipboardPreviewActivity
import info.loveyu.mfca.clipboard.ClipboardCleanerConfig
import info.loveyu.mfca.clipboard.ClipboardHistoryDbHelper
import info.loveyu.mfca.clipboard.ClipboardNotificationHelper
import info.loveyu.mfca.clipboard.ClipboardRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ClipboardHistoryTopBar(
    onCleanByTime: () -> Unit = {},
    onCleanPasswords: () -> Unit = {},
    onCleanVerificationCodes: () -> Unit = {}
) {
    var showOverflowMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("剪贴板历史") },
        actions = {
            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "菜单"
                    )
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("清理剪贴板内容") },
                        leadingIcon = {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null)
                        },
                        onClick = {
                            showOverflowMenu = false
                            onCleanByTime()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("清理密码") },
                        leadingIcon = {
                            Icon(Icons.Default.Password, contentDescription = null)
                        },
                        onClick = {
                            showOverflowMenu = false
                            onCleanPasswords()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("清理验证码") },
                        leadingIcon = {
                            Icon(Icons.Default.CleaningServices, contentDescription = null)
                        },
                        onClick = {
                            showOverflowMenu = false
                            onCleanVerificationCodes()
                        }
                    )
                }
            }
        }
    )
}

private data class TimeOption(val label: String, val days: Long?)

@OptIn(FlowPreview::class)
@Composable
fun ClipboardHistoryContent(
    onBack: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    refreshTrigger: Int = 0,
    cleanByTimeTrigger: Int = 0,
    cleanPasswordsTrigger: Int = 0,
    cleanVerificationCodesTrigger: Int = 0,
    onCleanTriggerConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var records by remember { mutableStateOf<List<ClipboardRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var totalCount by remember { mutableStateOf(0) }
    var searchKeyword by remember { mutableStateOf("") }
    var linkPickerUrls by remember { mutableStateOf<List<String>>(emptyList()) }

    // Dialog states
    var showTimeCleanDialog by remember { mutableStateOf(false) }
    var showPasswordCleanDialog by remember { mutableStateOf(false) }
    var showVerificationCodeCleanDialog by remember { mutableStateOf(false) }
    var selectedTimeOption by remember { mutableIntStateOf(0) }

    val dbHelper = remember { ClipboardHistoryDbHelper(context) }

    fun loadRecords() {
        scope.launch(Dispatchers.IO) {
            val result = dbHelper.query(
                keyword = searchKeyword.ifBlank { null },
                limit = 200
            )
            val count = dbHelper.count(keyword = searchKeyword.ifBlank { null })
            launch(Dispatchers.Main) {
                records = result
                totalCount = count
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadRecords() }

    val dbVersion by ClipboardHistoryDbHelper.changeVersion.collectAsState()
    LaunchedEffect(dbVersion) {
        if (dbVersion > 0) loadRecords()
    }

    LifecycleResumeEffect(Unit) {
        loadRecords()
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { searchKeyword }
            .debounce(300)
            .distinctUntilChanged()
            .collect { loadRecords() }
    }

    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            listState.scrollToItem(0)
            loadRecords()
        }
    }

    // Trigger dialogs from TopBar callbacks
    LaunchedEffect(cleanByTimeTrigger) {
        if (cleanByTimeTrigger > 0) {
            showTimeCleanDialog = true
            onCleanTriggerConsumed()
        }
    }

    LaunchedEffect(cleanPasswordsTrigger) {
        if (cleanPasswordsTrigger > 0) {
            showPasswordCleanDialog = true
            onCleanTriggerConsumed()
        }
    }

    LaunchedEffect(cleanVerificationCodesTrigger) {
        if (cleanVerificationCodesTrigger > 0) {
            showVerificationCodeCleanDialog = true
            onCleanTriggerConsumed()
        }
    }

    fun copyRecord(record: ClipboardRecord) {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Clipboard History", record.content)
        )
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.IO) {
            dbHelper.insertOrUpdate(record.content, record.contentType)
        }
    }

    var showSearchBar by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (searchKeyword.isNotBlank() && totalCount > 0) {
                Text(
                    text = "共 $totalCount 条记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (records.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无剪贴板记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        top = 6.dp,
                        end = 8.dp,
                        bottom = if (showSearchBar) 76.dp else 6.dp
                    )
                ) {
                    itemsIndexed(
                        items = records,
                        key = { _, record -> record.id }
                    ) { _, record ->
                        val urls = remember(record.content) { extractUrls(record.content) }
                        val isPureUrl = remember(record.content) { isSingleUrl(record.content) }

                        ClipboardRecordCard(
                            record = record,
                            urls = urls,
                            isPureUrl = isPureUrl,
                            onCopy = { copyRecord(record) },
                            onOpenDetail = {
                                ClipboardDetailActivity.start(context, record.id)
                            },
                            onOpenPreview = {
                                ClipboardPreviewActivity.start(context, record.id)
                            },
                            onTogglePin = {
                                scope.launch(Dispatchers.IO) {
                                    dbHelper.updatePinned(record.id, !record.pinned)
                                    loadRecords()
                                }
                            },
                            onOpenLink = {
                                when {
                                    urls.isEmpty() -> {}
                                    urls.size == 1 -> openUrl(context, urls.first())
                                    else -> linkPickerUrls = urls
                                }
                            },
                            onDelete = {
                                scope.launch(Dispatchers.IO) {
                                    if (record.notificationPinned) {
                                        record.notificationId?.let { nid ->
                                            ClipboardNotificationHelper.unpinNotification(
                                                context,
                                                nid
                                            )
                                        }
                                    }
                                    dbHelper.deleteById(record.id)
                                    launch(Dispatchers.Main) { loadRecords() }
                                }
                            },
                            onClick = {
                                if (isPureUrl && urls.size == 1) {
                                    openUrl(context, urls.first())
                                } else {
                                    ClipboardDetailActivity.start(context, record.id)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Floating search FAB + bottom search bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
        ) {
            FloatingSearchBar(
                visible = showSearchBar,
                keyword = searchKeyword,
                onKeywordChange = { searchKeyword = it },
                onRequestShow = { showSearchBar = true },
                onRequestHide = {
                    showSearchBar = false
                    searchKeyword = ""
                },
                placeholder = "搜索内容..."
            )
        }
    }

    // Link picker bottom sheet
    if (linkPickerUrls.isNotEmpty()) {
        LinkPickerSheet(
            urls = linkPickerUrls,
            onUrlClick = { url ->
                linkPickerUrls = emptyList()
                openUrl(context, url)
            },
            onDismiss = { linkPickerUrls = emptyList() }
        )
    }

    // Time clean dialog
    if (showTimeCleanDialog) {
        val timeOptions = remember {
            listOf(
                TimeOption("清理 30 天前", 30),
                TimeOption("清理 10 天前", 10),
                TimeOption("清理 7 天前", 7),
                TimeOption("清理 3 天前", 3),
                TimeOption("清理 24 小时前", null) // special: uses hours
            )
        }

        AlertDialog(
            onDismissRequest = { showTimeCleanDialog = false },
            title = { Text("清理剪贴板内容") },
            text = {
                Column(modifier = Modifier.selectableGroup()) {
                    timeOptions.forEachIndexed { index, option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedTimeOption == index,
                                    onClick = { selectedTimeOption = index },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedTimeOption == index,
                                onClick = null
                            )
                            Text(
                                text = option.label,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedTimeOption == timeOptions.size,
                                onClick = { selectedTimeOption = timeOptions.size },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedTimeOption == timeOptions.size,
                            onClick = null
                        )
                        Text(
                            text = "清理全部（未置顶）",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTimeCleanDialog = false
                    scope.launch(Dispatchers.IO) {
                        val deleted = if (selectedTimeOption == timeOptions.size) {
                            dbHelper.deleteAllUnpinned()
                        } else {
                            val opt = timeOptions[selectedTimeOption]
                            val cutoffMs = if (opt.days != null) {
                                System.currentTimeMillis() - opt.days * 24 * 60 * 60 * 1000L
                            } else {
                                System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                            }
                            dbHelper.deleteOlderThan(cutoffMs)
                        }
                        launch(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "已清理 $deleted 条记录",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimeCleanDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Password clean dialog
    if (showPasswordCleanDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordCleanDialog = false },
            title = { Text("清理密码") },
            text = { Text("确定要清理所有疑似包含密码的剪贴板记录吗？置顶记录不会被清理。") },
            confirmButton = {
                TextButton(onClick = {
                    showPasswordCleanDialog = false
                    scope.launch(Dispatchers.IO) {
                        val patterns = ClipboardCleanerConfig.loadPasswordPatterns(context)
                        val deleted = dbHelper.deleteByPattern(patterns)
                        launch(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "已清理 $deleted 条记录",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordCleanDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Verification code clean dialog
    if (showVerificationCodeCleanDialog) {
        AlertDialog(
            onDismissRequest = { showVerificationCodeCleanDialog = false },
            title = { Text("清理验证码") },
            text = { Text("确定要清理所有疑似包含验证码的剪贴板记录吗？置顶记录不会被清理。") },
            confirmButton = {
                TextButton(onClick = {
                    showVerificationCodeCleanDialog = false
                    scope.launch(Dispatchers.IO) {
                        val patterns =
                            ClipboardCleanerConfig.loadVerificationCodePatterns(context)
                        val deleted = dbHelper.deleteByPattern(patterns)
                        launch(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "已清理 $deleted 条记录",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showVerificationCodeCleanDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
