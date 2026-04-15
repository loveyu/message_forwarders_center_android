@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import info.loveyu.mfca.NotifyDetailActivity
import info.loveyu.mfca.notification.NotifyHistoryDbHelper
import info.loveyu.mfca.notification.NotifyHistoryDbHelper.Companion.changeVersion
import info.loveyu.mfca.notification.NotifyRecord
import info.loveyu.mfca.notification.TimeRange
import info.loveyu.mfca.util.IconCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

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
    drawerState: androidx.compose.material3.DrawerState,
    contentPadding: PaddingValues = PaddingValues()
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

    // Drawer and filter state (not persisted)
    var showTimeFilter by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

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

    // Auto-refresh when NotifyOutput inserts new records
    val dbVersion by changeVersion.collectAsState()
    LaunchedEffect(dbVersion) {
        if (dbVersion > 0) loadRecords()
    }

    // Refresh when tab becomes active (e.g. returning from notification click or detail activity)
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(0.75f),
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0)
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

                    // Source rule filter
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

                    // Output name filter
                    Text(
                        text = "输出名称",
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

                    // Time filter toggle
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

                    // Clear filtered records
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
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            SearchBar(keyword = searchKeyword, onKeywordChange = { searchKeyword = it })

            // Time filter row (conditionally shown)
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

            // Active filter indicators
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
                    Text(text = "暂无通知记录", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    itemsIndexed(items = records, key = { _, record -> record.id }) { _, record ->
                        NotifyRecordCard(
                            record = record,
                            isHighlighted = record.id == highlightId,
                            onClick = { NotifyDetailActivity.start(context, record.id) },
                            onLongClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("通知内容", record.content))
                                Toast.makeText(context, "已复制内容", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
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

@Composable
private fun FilterOptionList(
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FilterOptionItem(label = "全部", isSelected = selected == null) { onSelect(null) }
        options.forEach { option ->
            FilterOptionItem(label = option, isSelected = selected == option) { onSelect(option) }
        }
    }
}

@Composable
private fun FilterOptionItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun FilterIndicatorChip(label: String, onClear: () -> Unit) {
    AssistChip(
        onClick = onClear,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier.height(28.dp)
    )
}

// Keep old name for compatibility
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotifyHistoryScreen(
    onBack: () -> Unit,
    highlightNotifyId: Int? = null,
    modifier: Modifier = Modifier
) {
    val drawerState = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    NotifyHistoryContent(onBack = onBack, highlightNotifyId = highlightNotifyId, drawerState = drawerState)
}

@Composable
private fun SearchBar(keyword: String, onKeywordChange: (String) -> Unit) {
    OutlinedTextField(
        value = keyword,
        onValueChange = onKeywordChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(48.dp),
        placeholder = { Text("搜索标题、内容...", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(24.dp)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun TimeRangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, style = MaterialTheme.typography.bodySmall) }, modifier = Modifier.height(32.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotifyRecordCard(record: NotifyRecord, isHighlighted: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val targetColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val animatedColor by animateColorAsState(targetValue = targetColor, animationSpec = tween(durationMillis = 500), label = "highlight")

    // Async icon loading
    val context = LocalContext.current
    var iconBitmap by remember(record.iconUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(record.iconUrl) {
        if (!record.iconUrl.isNullOrBlank()) {
            iconBitmap = IconCacheManager(context).getIcon(record.iconUrl, null)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = animatedColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Icon
            if (iconBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = iconBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).then(
                        Modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = record.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatRelativeTime(record.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = record.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}分钟前"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}小时前"
        diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)}天前"
        else -> formatAbsoluteTime(timestamp)
    }
}

private fun formatAbsoluteTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
