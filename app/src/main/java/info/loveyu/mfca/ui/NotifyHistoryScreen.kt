package info.loveyu.mfca.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.notification.NotifyHistoryDbHelper
import info.loveyu.mfca.notification.NotifyRecord
import info.loveyu.mfca.notification.TimeRange
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

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun NotifyHistoryScreen(
    onBack: () -> Unit,
    highlightNotifyId: Int? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var records by remember { mutableStateOf<List<NotifyRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var totalCount by remember { mutableStateOf(0) }

    // 搜索和筛选状态
    var searchKeyword by remember { mutableStateOf("") }
    var selectedSourceRule by remember { mutableStateOf<String?>(null) }
    var selectedOutputName by remember { mutableStateOf<String?>(null) }
    var selectedTimeRange by remember { mutableStateOf(TimeRange.ALL) }

    // 筛选选项
    var sourceRuleOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var outputNameOptions by remember { mutableStateOf<List<String>>(emptyList()) }

    // 详情
    var selectedRecord by remember { mutableStateOf<NotifyRecord?>(null) }

    // 清空确认
    var showClearDialog by remember { mutableStateOf(false) }

    // 高亮状态
    var highlightId by remember { mutableStateOf<Long?>(null) }

    val dbHelper = remember { NotifyHistoryDbHelper(context) }

    // 加载数据
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

    // 初始加载
    LaunchedEffect(Unit) {
        loadRecords()
    }

    // 搜索防抖
    LaunchedEffect(Unit) {
        snapshotFlow { searchKeyword }
            .debounce(300)
            .distinctUntilChanged()
            .collect { loadRecords() }
    }

    // 高亮定位
    LaunchedEffect(highlightNotifyId) {
        if (highlightNotifyId != null) {
            val position = dbHelper.getPositionByNotifyId(highlightNotifyId)
            if (position >= 0) {
                delay(300) // 等待列表渲染
                listState.animateScrollToItem(position)
                // 查找该记录的 db id
                val record = dbHelper.queryByNotifyId(highlightNotifyId)
                if (record != null) {
                    highlightId = record.id
                    delay(2000)
                    highlightId = null
                }
            }
        }
    }

    if (selectedRecord != null) {
        NotifyDetailScreen(
            record = selectedRecord!!,
            onBack = { selectedRecord = null },
            onDelete = {
                scope.launch(Dispatchers.IO) {
                    dbHelper.deleteById(selectedRecord!!.id)
                    launch(Dispatchers.Main) {
                        selectedRecord = null
                        loadRecords()
                    }
                }
            }
        )
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("通知历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "清空"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 搜索栏
            SearchBar(
                keyword = searchKeyword,
                onKeywordChange = { searchKeyword = it }
            )

            // 筛选栏
            FilterBar(
                sourceRules = sourceRuleOptions,
                outputNames = outputNameOptions,
                selectedSourceRule = selectedSourceRule,
                selectedOutputName = selectedOutputName,
                selectedTimeRange = selectedTimeRange,
                onSourceRuleChange = {
                    selectedSourceRule = it
                    loadRecords()
                },
                onOutputNameChange = {
                    selectedOutputName = it
                    loadRecords()
                },
                onTimeRangeChange = {
                    selectedTimeRange = it
                    loadRecords()
                }
            )

            // 计数
            Text(
                text = "共 $totalCount 条记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // 列表
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (records.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp, vertical = 8.dp
                    )
                ) {
                    itemsIndexed(
                        items = records,
                        key = { _, record -> record.id }
                    ) { _, record ->
                        NotifyRecordCard(
                            record = record,
                            isHighlighted = record.id == highlightId,
                            onClick = { selectedRecord = record },
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

    // 清空确认对话框
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空所有记录") },
            text = { Text("确定要清空所有通知历史记录吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        dbHelper.deleteAll()
                        launch(Dispatchers.Main) {
                            showClearDialog = false
                            loadRecords()
                        }
                    }
                }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SearchBar(
    keyword: String,
    onKeywordChange: (String) -> Unit
) {
    OutlinedTextField(
        value = keyword,
        onValueChange = onKeywordChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("搜索标题、内容...") },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null)
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    sourceRules: List<String>,
    outputNames: List<String>,
    selectedSourceRule: String?,
    selectedOutputName: String?,
    selectedTimeRange: TimeRange,
    onSourceRuleChange: (String?) -> Unit,
    onOutputNameChange: (String?) -> Unit,
    onTimeRangeChange: (TimeRange) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 来源规则筛选
            FilterDropdown(
                label = "来源规则",
                options = sourceRules,
                selected = selectedSourceRule,
                onSelect = onSourceRuleChange,
                modifier = Modifier.weight(1f)
            )

            // 输出名称筛选
            FilterDropdown(
                label = "输出名称",
                options = outputNames,
                selected = selectedOutputName,
                onSelect = onOutputNameChange,
                modifier = Modifier.weight(1f)
            )
        }

        // 时间范围
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TimeRangeChip("全部", selectedTimeRange == TimeRange.ALL) {
                onTimeRangeChange(TimeRange.ALL)
            }
            TimeRangeChip("今日", selectedTimeRange == TimeRange.TODAY) {
                onTimeRangeChange(TimeRange.TODAY)
            }
            TimeRangeChip("7天", selectedTimeRange == TimeRange.WEEK) {
                onTimeRangeChange(TimeRange.WEEK)
            }
            TimeRangeChip("30天", selectedTimeRange == TimeRange.MONTH) {
                onTimeRangeChange(TimeRange.MONTH)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDropdown(
    label: String,
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected ?: "",
            onValueChange = {},
            readOnly = true,
            placeholder = { Text(label, style = MaterialTheme.typography.bodySmall) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // 清除选项
            DropdownMenuItem(
                text = { Text("全部", style = MaterialTheme.typography.bodySmall) },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun TimeRangeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        modifier = Modifier.height(32.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotifyRecordCard(
    record: NotifyRecord,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val targetColor = if (isHighlighted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        CardDefaults.cardColors().containerColor
    }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 500),
        label = "highlight"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(containerColor = animatedColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatRelativeTime(record.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 内容预览
            Text(
                text = record.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // 标签
            if (!record.sourceRule.isNullOrBlank() || !record.outputName.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!record.sourceRule.isNullOrBlank()) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    record.sourceRule,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                record.outputName,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotifyDetailScreen(
    record: NotifyRecord,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var showRawData by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val text = buildString {
                            append("标题: ${record.title}\n")
                            append("内容: ${record.content}\n")
                            append("时间: ${formatAbsoluteTime(record.createdAt)}\n")
                            append("来源规则: ${record.sourceRule ?: "未知"}\n")
                            append("输出名称: ${record.outputName}\n")
                            append("渠道: ${record.channel}\n")
                            record.tag?.let { append("标签: $it\n") }
                            record.group?.let { append("分组: $it\n") }
                            record.rawData?.let { append("\n原始数据:\n$it") }
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("通知详情", text))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("\uD83D\uDCCB", style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题
            Text(
                text = record.title,
                style = MaterialTheme.typography.headlineSmall
            )

            // 元信息
            DetailInfoRow("时间", formatAbsoluteTime(record.createdAt))
            record.sourceRule?.let { DetailInfoRow("来源规则", it) }
            DetailInfoRow("输出名称", record.outputName)
            DetailInfoRow("渠道", record.channel)
            record.tag?.let { DetailInfoRow("标签", it) }
            record.group?.let { DetailInfoRow("分组", it) }
            record.iconUrl?.let { DetailInfoRow("图标", it) }
            if (record.popup) {
                DetailInfoRow("弹出通知", "是")
            }
            if (record.persistent) {
                DetailInfoRow("常驻通知", "是")
            }

            // 分隔线
            Spacer(modifier = Modifier.height(4.dp))

            // 内容全文
            Text(
                text = "内容",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = record.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }

            // 原始数据
            if (!record.rawData.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { showRawData = !showRawData }) {
                    Text(if (showRawData) "收起原始数据" else "展开原始数据")
                }
                if (showRawData) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = formatRawData(record.rawData),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}分钟前"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}小时前"
        diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)}天前"
        else -> formatAbsoluteTime(timestamp)
    }
}

private fun formatAbsoluteTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatRawData(rawData: String): String {
    return try {
        val trimmed = rawData.trimStart()
        if (trimmed.startsWith("{")) {
            org.json.JSONObject(rawData).toString(2)
        } else if (trimmed.startsWith("[")) {
            org.json.JSONArray(rawData).toString(2)
        } else {
            rawData
        }
    } catch (e: Exception) {
        rawData
    }
}
