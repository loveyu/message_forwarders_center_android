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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    LaunchedEffect(dbVersion) { if (dbVersion > 0) loadRecords() }
    LifecycleResumeEffect(Unit) { loadRecords(); onPauseOrDispose { } }
    LaunchedEffect(Unit) {
        snapshotFlow { searchKeyword }
            .debounce(300).distinctUntilChanged().collect { loadRecords() }
    }
    LaunchedEffect(highlightNotifyId) {
        if (highlightNotifyId != null) {
            val position = dbHelper.getPositionByNotifyId(highlightNotifyId)
            if (position >= 0) {
                delay(300); listState.animateScrollToItem(position)
                val record = dbHelper.queryByNotifyId(highlightNotifyId)
                if (record != null) { highlightId = record.id; delay(2000); highlightId = null }
            }
        }
    }
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) { listState.scrollToItem(0); loadRecords() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            FilterDrawerContent(
                sourceRuleOptions = sourceRuleOptions,
                selectedSourceRule = selectedSourceRule,
                onSourceRuleSelect = { selectedSourceRule = it; loadRecords() },
                outputNameOptions = outputNameOptions,
                selectedOutputName = selectedOutputName,
                onOutputNameSelect = { selectedOutputName = it; loadRecords() },
                selectedTimeRange = selectedTimeRange,
                onTimeRangeSelect = { selectedTimeRange = it; loadRecords() },
                showTimeFilter = showTimeFilter,
                onToggleTimeFilter = { showTimeFilter = it },
                onClear = { showClearConfirmDialog = true },
            )
        },
    ) {
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
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
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
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        itemsIndexed(
                            items = records,
                            key = { _, record -> record.id }
                        ) { _, record ->
                            val isHighlighted = record.id == highlightId
                            val urls = remember(record.content) {
                                extractUrls(record.content)
                            }
                            NotifyRecordCard(
                                record = record,
                                urls = urls,
                                isHighlighted = isHighlighted,
                                onCopy = {
                                    val clipboard =
                                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText("通知内容", record.content)
                                    )
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                },
                                onClick = {
                                    NotifyDetailActivity.start(context, record.id)
                                },
                                onOpenLink = {
                                    if (urls.isEmpty()) {
                                        NotifyDetailActivity.start(context, record.id)
                                    } else if (urls.size == 1) {
                                        openUrl(context, urls.first())
                                    } else {
                                        linkPickerUrls = urls
                                    }
                                },
                                onDelete = {
                                    scope.launch(Dispatchers.IO) {
                                        dbHelper.deleteById(record.id)
                                        loadRecords()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

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

    if (showClearConfirmDialog) {
        ClearNotifyHistoryDialog(
            onConfirm = {
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
            },
            onDismiss = { showClearConfirmDialog = false }
        )
    }
}
