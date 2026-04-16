@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.R
import info.loveyu.mfca.util.LogEntry
import info.loveyu.mfca.util.LogLevel
import info.loveyu.mfca.util.LogManager
import info.loveyu.mfca.util.Preferences

private fun LogLevel.toColor(): androidx.compose.ui.graphics.Color = when (this) {
    LogLevel.ERROR -> info.loveyu.mfca.ui.theme.LogErrorColor
    LogLevel.WARN -> info.loveyu.mfca.ui.theme.LogWarnColor
    LogLevel.INFO -> info.loveyu.mfca.ui.theme.LogInfoColor
    LogLevel.DEBUG -> info.loveyu.mfca.ui.theme.LogDebugColor
}

@Composable
fun LogSection(
    preferences: Preferences,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var isPaused by remember { mutableStateOf(LogManager.isPaused()) }
    var isFileLogging by remember { mutableStateOf(LogManager.isFileLoggingEnabled()) }
    var isAllLogcatEnabled by remember { mutableStateOf(LogManager.isAllLogcatEnabled()) }

    val logs by LogManager.logs.collectAsState()
    val currentLogLevel by LogManager.logLevelFlow.collectAsState()

    var expandedLogLevel by remember { mutableStateOf(false) }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.log_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = currentLogLevel.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { expandedLogLevel = true }
                    )
                    if (expandedLogLevel) {
                        AlertDialog(
                            onDismissRequest = { expandedLogLevel = false },
                            title = { Text("选择日志等级") },
                            text = {
                                Column {
                                    LogLevel.entries.forEach { level ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    LogManager.setLogLevel(level, preferences)
                                                    LogManager.appendLog(
                                                        LogLevel.INFO,
                                                        "UI",
                                                        "日志等级已切换为 ${level.name}"
                                                    )
                                                    expandedLogLevel = false
                                                }
                                                .padding(vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = level.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = if (level == currentLogLevel)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.onSurface
                                            )
                                            if (level == currentLogLevel) {
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = "✓",
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { expandedLogLevel = false }) {
                                    Text("取消")
                                }
                            }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = {
                        if (isPaused) LogManager.resumeLogs() else LogManager.pauseLogs()
                        isPaused = LogManager.isPaused()
                    }) { Text(if (isPaused) "继续" else "暂停", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = { LogManager.clearLogs() }) { Text("清空", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = {
                        if (isFileLogging) {
                            LogManager.setFileLoggingEnabled(false, preferences)
                            isFileLogging = false
                            Toast.makeText(context, "已停止保存日志", Toast.LENGTH_SHORT).show()
                        } else {
                            LogManager.setFileLoggingEnabled(true, preferences)
                            isFileLogging = true
                            Toast.makeText(context, "已开始保存日志到文件", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(if (isFileLogging) "停存" else "存日志", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = {
                        val newState = !isAllLogcatEnabled
                        LogManager.logWarn("UI", "Toggle logcat all: $isAllLogcatEnabled -> $newState")
                        LogManager.setAllLogcatEnabled(newState, preferences)
                        isAllLogcatEnabled = LogManager.isAllLogcatEnabled()
                    }) { Text(if (isAllLogcatEnabled) "全Log" else "Logcat", style = MaterialTheme.typography.labelSmall) }
                }
            }

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState
            ) {
                items(logs, key = { it.id }) { entry ->
                    Text(
                        text = entry.formatted,
                        style = MaterialTheme.typography.bodySmall,
                        color = entry.level.toColor(),
                        softWrap = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("log", entry.formatted))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            }
                    )
                }
            }
        }
    }
}
