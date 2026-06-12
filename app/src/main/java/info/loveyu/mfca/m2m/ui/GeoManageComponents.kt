@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.m2m.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.R
import info.loveyu.mfca.m2m.geo.GeoCacheState
import info.loveyu.mfca.m2m.geo.GeoFileType
import info.loveyu.mfca.m2m.geo.formatFileSize

@Composable
fun GeoFileCard(
    type: GeoFileType,
    cacheState: GeoCacheState,
    effectiveUrl: String,
    hasDefault: Boolean,
    isWorking: Boolean,
    progress: Float,
    errorText: String?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onEditUrl: () -> Unit,
    onResetUrl: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        when (type) {
                            GeoFileType.GEOIP -> R.string.geo_type_geoip
                            GeoFileType.GEOSITE -> R.string.geo_type_geosite
                            GeoFileType.COUNTRY -> R.string.geo_type_country
                            GeoFileType.ASN -> R.string.geo_type_asn
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_options),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                if (isWorking) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.geo_downloading))
                                    }
                                } else {
                                    Text(
                                        stringResource(
                                            if (cacheState.isCached) R.string.geo_redownload
                                            else R.string.geo_download,
                                        ),
                                    )
                                }
                            },
                            onClick = {
                                showMenu = false
                                if (!isWorking) onDownload()
                            },
                            enabled = !isWorking && effectiveUrl.isNotBlank(),
                        )
                        if (cacheState.isCached) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.geo_delete_cache)) },
                                onClick = { showMenu = false; onDelete() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.geo_edit_url)) },
                            onClick = { showMenu = false; onEditUrl() },
                        )
                        if (hasDefault) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.geo_reset_url)) },
                                onClick = { showMenu = false; onResetUrl() },
                            )
                        }
                    }
                }
            }

            if (isWorking && progress > 0f) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else if (isWorking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    text = if (cacheState.isCached) {
                        val sizeText = formatFileSize(cacheState.fileSize)
                        stringResource(R.string.geo_status_cached, sizeText)
                    } else {
                        stringResource(R.string.geo_status_not_cached)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cacheState.isCached) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = if (effectiveUrl.isNotBlank()) effectiveUrl
                else stringResource(R.string.geo_no_default_url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun EditUrlDialog(
    editingUrl: String,
    onUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.geo_url_dialog_title)) },
        text = {
            OutlinedTextField(
                value = editingUrl,
                onValueChange = onUrlChange,
                label = { Text(stringResource(R.string.geo_url_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(stringResource(R.string.geo_url_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.geo_url_dialog_cancel))
            }
        },
    )
}

@Composable
fun ConfirmDeleteSingleDialog(
    type: GeoFileType,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.geo_delete_confirm, type.label)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun ConfirmDeleteAllDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.geo_delete_all_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("全部删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun ConfirmResetSingleDialog(
    type: GeoFileType,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.geo_reset_confirm, type.label)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("重置") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun ConfirmResetAllDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.geo_reset_all_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("全部重置") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
