package info.loveyu.mfca

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.util.ConfigBackupManager

@Composable
fun ConfigBackupDialog(
    backupList: List<ConfigBackupManager.BackupInfo>,
    onDismiss: () -> Unit,
    onOpenFile: (ConfigBackupManager.BackupInfo) -> Unit,
    onRestore: (ConfigBackupManager.BackupInfo) -> Unit,
    onDelete: (ConfigBackupManager.BackupInfo) -> Unit,
    onClearAll: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_list_title)) },
        text = {
            if (backupList.isEmpty()) {
                Text(stringResource(R.string.backup_empty))
            } else {
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    items(backupList) { backup ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = backup.displayName, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onOpenFile(backup) }) { Text("打开") }
                            TextButton(onClick = { onRestore(backup) }) { Text(stringResource(R.string.backup_restore)) }
                            TextButton(onClick = { onDelete(backup) }) { Text(stringResource(R.string.backup_delete)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (backupList.isNotEmpty()) {
                TextButton(onClick = onClearAll) { Text(stringResource(R.string.backup_clear_all)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun ConfigActionButtons(
    isLoading: Boolean,
    onDownload: () -> Unit,
    onReload: () -> Unit,
    onShowBackups: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onDownload,
            modifier = Modifier.weight(1f),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.download_config))
            }
        }

        OutlinedButton(
            onClick = onReload,
            modifier = Modifier.weight(1f),
            enabled = !isLoading
        ) { Text(stringResource(R.string.reload_config)) }

        OutlinedButton(onClick = onShowBackups, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.restore_config))
        }
    }
}

@Composable
fun ConfigOpenEditorButton(onOpen: () -> Unit) {
    OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("用外部编辑器打开当前配置")
    }
}
