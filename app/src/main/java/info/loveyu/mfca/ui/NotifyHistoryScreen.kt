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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import info.loveyu.mfca.NotifyDetailActivity
import info.loveyu.mfca.notification.NotifyHistoryDbHelper
import info.loveyu.mfca.notification.NotifyHistoryDbHelper.Companion.changeVersion
import info.loveyu.mfca.notification.NotifyRecord
import info.loveyu.mfca.notification.TimeRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun NotifyHistoryTopBar(onMenuClick: () -> Unit) {
    TopAppBar(
        title = { Text("通知管理") },
        actions = {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "菜单"
                )
            }
        }
    )
}

@OptIn(FlowPreview::class)
@Composable
fun NotifyHistoryContent(
    onBack: () -> Unit,
    highlightNotifyId: Int? = null,
    drawerState: DrawerState,
    contentPadding: PaddingValues = PaddingValues(),
    refreshTrigger: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var records by remember { mutableStateOf<List<NotifyRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var totalCount by remember { mutableStateOf(0) }

    var searchKeyword by remember { mutableStateOf("") }
    var selectedSourceRule by remember { mutableStateOf<String?>(null) }
    var selectedOutputName by remember { mutableStateOf<String?>(null) }
    var selectedTimeRange by remember { mutableStateOf(TimeRange.ALL) }

    var sourceRuleOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var outputNameOptions by remember { mutableStateOf<List<String>>(emptyList()) }

    var highlightId by remember { mutableStateOf<Long?>(null) }

    var showTimeFilter by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var linkPickerUrls by remember { mutableStateOf<List<String>>(emptyList()) }

    val dbHelper = remember { NotifyHistoryDbHelper(context) }

    fun loadRecords() {
        scope.launch(Dispatchers.IO) {
            val result = dbHelper.query(
                keyword = searchKeyword.ifBlank { null },
                sourceRule = selectedSourceRule,
                outputName = selectedOutputName,
                timeRange = selectedTimeRange,
                limit = 200
            )
            val count = dbHelper.count(
                keyword = searchKeyword.ifBlank { null },
                sourceRule = selectedSourceRule,
                outputName = selectedOutputName,
                timeRange = selectedTimeRange
            )
            val rules = dbHelper.getDistinctSourceRules()
            val outputs = dbHelper.getDistinctOutputNames()
            launch(Dispatchers.Main) {
                records = result
                totalCount = count
                sourceRuleOptions = rules
                outputNameOptions = outputs
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadRecords() }

    val dbVersion by changeVersion.collectAsState()
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

    LaunchedEffect(highlightNotifyId) {
        if (highlightNotifyId != null) {
            val position = dbHelper.getPositionByNotifyId(highlightNotifyId)
            if (position >= 0) {
                delay(300)
                listState.animateScrollToItem(position)
                val record = dbHelper.queryByNotifyId(highlightNotifyId)
                if (record != null) {
                    highlightId = record.id
                    delay(2000)
                    highlightId = null
                }
            }
        }
    }

    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            listState.scrollToItem(0)
            loadRecords()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(0.75f),
                windowInsets = WindowInsets(0)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .padding(contentPadding)
                ) {
                    Text(
                        text = "筛选设置",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Text(
                        text = "来源规则",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    FilterOptionList(
                        options = sourceRuleOptions,
                        selected = selectedSourceRule,
                        onSelect = {
                            selectedSourceRule = it
                            loadRecords()
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "输出��称",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    FilterOptionList(
                        options = outputNameOptions,
                        selected = selectedOutputName,
                        onSelect = {
                            selectedOutputName = it
                            loadRecords()
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("显示时间筛选")
                        Switch(
                            checked = showTimeFilter,
                            onCheckedChange = { showTimeFilter = it }
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showClearConfirmDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("清空当前筛选记录")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    ) {
        // Main content
        var showSearchBar by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (showTimeFilter) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TimeRangeChip("全部", selectedTimeRange == TimeRange.ALL) { selectedTimeRange = TimeRange.ALL; loadRecords() }
                        TimeRangeChip("今日", selectedTimeRange == TimeRange.TODAY) { selectedTimeRange = TimeRange.TODAY; loadRecords() }
                        TimeRangeChip("7天", selectedTimeRange == TimeRange.WEEK) { selectedTimeRange = TimeRange.WEEK; loadRecords() }
                        TimeRangeChip("30天", selectedTimeRange == TimeRange.MONTH) { selectedTimeRange = TimeRange.MONTH; loadRecords() }
                    }
                }

                val hasActiveFilter = selectedSourceRule != null || selectedOutputName != null || (showTimeFilter && selectedTimeRange != TimeRange.ALL)
                if (hasActiveFilter) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        selectedSourceRule?.let {
                            FilterIndicatorChip("来源: $it") {
                                selectedSourceRule = null
                                loadRecords()
                            }
                        }
                        selectedOutputName?.let {
                            FilterIndicatorChip("输出: $it") {
                                selectedOutputName = null
                                loadRecords()
                            }
                        }
                        if (showTimeFilter && selectedTimeRange != TimeRange.ALL) {
                            val label = when (selectedTimeRange) {
                                TimeRange.TODAY -> "今日"
                                TimeRange.WEEK -> "7天"
                                TimeRange.MONTH -> "30天"
                                TimeRange.ALL -> "全部"
                            }
                            FilterIndicatorChip("时间: $label") {
                                selectedTimeRange = TimeRange.ALL
                                loadRecords()
                            }
                        }
                    }
                }

                if (hasActiveFilter && totalCount > 0) {
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
                            text = "暂无通知记录",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(
                            start = 4.dp,
                            top = 4.dp,
                            end = 4.dp,
                            bottom = if (showSearchBar) 76.dp else 4.dp
                        )
                    ) {
                        itemsIndexed(items = records, key = { _, record -> record.id }) { _, record ->
                            val urls = remember(record.content) { extractUrls(record.content) }
                            NotifyRecordCard(
                                record = record,
                                urls = urls,
                                isHighlighted = record.id == highlightId,
                                onClick = { NotifyDetailActivity.start(context, record.id) },
                                onCopy = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("通知内容", record.content))
                                    Toast.makeText(context, "已复制内容", Toast.LENGTH_SHORT).show()
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
                                        dbHelper.deleteById(record.id)
                                        launch(Dispatchers.Main) { loadRecords() }
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
                    placeholder = "搜索标题、内容..."
                )
            }
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

    // Clear confirmation dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("清空筛选记录") },
            text = { Text("确定要清空当前筛选条件下的所有通知记录吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        dbHelper.deleteFiltered(
                            keyword = searchKeyword.ifBlank { null },
                            sourceRule = selectedSourceRule,
                            outputName = selectedOutputName,
                            timeRange = selectedTimeRange
                        )
                        launch(Dispatchers.Main) {
                            showClearConfirmDialog = false
                            loadRecords()
                        }
                    }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) { Text("取消") }
            }
        )
    }
}

// Keep old name for compatibility
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotifyHistoryScreen(
    onBack: () -> Unit,
    highlightNotifyId: Int? = null,
    modifier: Modifier = Modifier,
    refreshTrigger: Int = 0
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    NotifyHistoryContent(
        onBack = onBack,
        highlightNotifyId = highlightNotifyId,
        drawerState = drawerState,
        refreshTrigger = refreshTrigger
    )
}
