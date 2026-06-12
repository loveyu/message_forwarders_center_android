package info.loveyu.mfca.m2m.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.loveyu.mfca.R
import info.loveyu.mfca.m2m.models.LogLine
import info.loveyu.mfca.m2m.models.LogSource

@Composable
fun LogLineItem(line: LogLine, index: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Text(
        text = line.text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        ),
        color = if (line.isStderr) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface,
        softWrap = true,
        modifier = modifier.fillMaxWidth().padding(vertical = 1.dp).pointerInput(index) {
            detectTapGestures {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log_line", line.text))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }
        },
    )
}

@Composable
fun LogOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSwitchSource: () -> Unit,
    onClearLogs: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("切换日志来源") },
            onClick = { onDismiss(); onSwitchSource() },
        )
        DropdownMenuItem(
            text = { Text("清空日志") },
            onClick = { onDismiss(); onClearLogs() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.vpn_export_diagnostics)) },
            onClick = { onDismiss(); onExportDiagnostics() },
        )
    }
}

@Composable
fun LogSourceMenu(
    expanded: Boolean,
    sources: List<LogSource>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        sources.forEachIndexed { index, source ->
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedIndex == index, onClick = null)
                        Text(text = source.label, modifier = Modifier.padding(start = 8.dp))
                    }
                },
                onClick = { onSelect(index) },
            )
        }
    }
}
