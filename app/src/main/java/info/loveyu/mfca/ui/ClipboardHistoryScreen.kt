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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import info.loveyu.mfca.ClipboardDetailActivity
import info.loveyu.mfca.ClipboardPreviewActivity
import info.loveyu.mfca.clipboard.ClipboardHistoryDbHelper
import info.loveyu.mfca.clipboard.ClipboardNotificationHelper
import info.loveyu.mfca.clipboard.ClipboardRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ClipboardHistoryTopBar() {
    TopAppBar(
        title = { Text("剪贴板历史") }
    )
}

@OptIn(FlowPreview::class)
@Composable
fun ClipboardHistoryContent(
    onBack: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    refreshTrigger: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var records by remember { mutableStateOf<List<ClipboardRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var totalCount by remember { mutableStateOf(0) }
    var searchKeyword by remember { mutableStateOf("") }
    var linkPickerUrls by remember { mutableStateOf<List<String>>(emptyList()) }

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
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
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
}
