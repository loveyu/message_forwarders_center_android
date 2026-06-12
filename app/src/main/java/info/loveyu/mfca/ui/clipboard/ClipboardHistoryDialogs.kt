@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.ui.clipboard

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.clipboard.ClipboardCleanerConfig
import info.loveyu.mfca.clipboard.ClipboardHistoryDbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope

private data class TimeOption(val label: String, val days: Long?)

@Composable
fun TimeCleanDialog(
    dbHelper: ClipboardHistoryDbHelper,
    onDismiss: () -> Unit,
    onDeleted: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedTimeOption by remember { mutableIntStateOf(0) }
    val timeOptions = remember {
        listOf(
            TimeOption("清理 30 天前", 30),
            TimeOption("清理 10 天前", 10),
            TimeOption("清理 7 天前", 7),
            TimeOption("清理 3 天前", 3),
            TimeOption("清理 24 小时前", null),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
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
                        RadioButton(selected = selectedTimeOption == index, onClick = null)
                        Text(text = option.label, modifier = Modifier.padding(start = 8.dp))
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
                    Text(text = "清理全部（未置顶）", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
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
                    launch(Dispatchers.Main) { onDeleted(deleted) }
                }
            }) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
fun PasswordCleanDialog(
    dbHelper: ClipboardHistoryDbHelper,
    onDismiss: () -> Unit,
    onDeleted: (Int) -> Unit,
) {
    var show by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (!show) return

    AlertDialog(
        onDismissRequest = {
            show = false
            onDismiss()
        },
        title = { Text("清理密码") },
        text = { Text("确定要清理所有疑似包含密码的剪贴板记录吗？置顶记录不会被清理。") },
        confirmButton = {
            TextButton(onClick = {
                show = false
                onDismiss()
                scope.launch(Dispatchers.IO) {
                    val patterns = ClipboardCleanerConfig.loadPasswordPatterns(context)
                    val deleted = dbHelper.deleteByPattern(patterns)
                    launch(Dispatchers.Main) { onDeleted(deleted) }
                }
            }) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                show = false
                onDismiss()
            }) {
                Text("取消")
            }
        },
    )
}

@Composable
fun VerificationCodeCleanDialog(
    dbHelper: ClipboardHistoryDbHelper,
    onDismiss: () -> Unit,
    onDeleted: (Int) -> Unit,
) {
    var show by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (!show) return

    AlertDialog(
        onDismissRequest = {
            show = false
            onDismiss()
        },
        title = { Text("清理验证码") },
        text = { Text("确定要清理所有疑似包含验证码的剪贴板记录吗？置顶记录不会被清理。") },
        confirmButton = {
            TextButton(onClick = {
                show = false
                onDismiss()
                scope.launch(Dispatchers.IO) {
                    val patterns =
                        ClipboardCleanerConfig.loadVerificationCodePatterns(context)
                    val deleted = dbHelper.deleteByPattern(patterns)
                    launch(Dispatchers.Main) { onDeleted(deleted) }
                }
            }) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                show = false
                onDismiss()
            }) {
                Text("取消")
            }
        },
    )
}
