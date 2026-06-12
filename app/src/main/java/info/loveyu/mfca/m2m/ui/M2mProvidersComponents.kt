@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package info.loveyu.mfca.m2m.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.R
import info.loveyu.mfca.m2m.core.M2mManager
import info.loveyu.mfca.m2m.models.M2mProviderInfo
import info.loveyu.mfca.m2m.models.M2mProviderType
import info.loveyu.mfca.m2m.models.M2mVehicleType

@Composable
fun ProviderCard(
    provider: M2mProviderInfo,
    isWorking: Boolean,
    errorText: String?,
    onUpdate: () -> Unit,
    onShowDetail: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val hasUpdatedAt = provider.hasValidUpdatedAt()

    Card(
        modifier = Modifier.combinedClickable(
            onClick = onShowDetail,
            onDoubleClick = {
                if (!isWorking && M2mManager.isApiReady()) onUpdate()
            },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = when (provider.type) {
                                M2mProviderType.Proxy -> stringResource(R.string.providers_type_proxy)
                                M2mProviderType.Rule -> stringResource(R.string.providers_type_rule)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = when (provider.vehicleType) {
                                M2mVehicleType.HTTP -> stringResource(R.string.providers_vehicle_http)
                                M2mVehicleType.File -> stringResource(R.string.providers_vehicle_file)
                                M2mVehicleType.Compatible -> stringResource(R.string.providers_vehicle_compatible)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_options)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.providers_update)) },
                            onClick = {
                                showMenu = false
                                if (!isWorking) onUpdate()
                            },
                            enabled = !isWorking && M2mManager.isApiReady(),
                            leadingIcon = {
                                if (isWorking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                        )
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (provider.type == M2mProviderType.Proxy) {
                        Text(
                            text = stringResource(R.string.providers_proxy_count, provider.proxyCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.providers_rule_count, provider.ruleCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (hasUpdatedAt) {
                        Text(
                            text = stringResource(
                                R.string.providers_updated_at, provider.updatedAt
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (isWorking) {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).height(2.dp),
                    strokeWidth = 2.dp,
                )
            }

            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
fun ProviderDetailDialog(
    provider: M2mProviderInfo,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(provider.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow(
                    label = stringResource(R.string.providers_detail_type),
                    value = when (provider.type) {
                        M2mProviderType.Proxy -> stringResource(R.string.providers_type_proxy)
                        M2mProviderType.Rule -> stringResource(R.string.providers_type_rule)
                    },
                )
                DetailRow(
                    label = stringResource(R.string.providers_detail_vehicle),
                    value = when (provider.vehicleType) {
                        M2mVehicleType.HTTP -> stringResource(R.string.providers_vehicle_http)
                        M2mVehicleType.File -> stringResource(R.string.providers_vehicle_file)
                        M2mVehicleType.Compatible -> stringResource(R.string.providers_vehicle_compatible)
                    },
                )
                if (provider.type == M2mProviderType.Proxy) {
                    DetailRow(
                        label = stringResource(R.string.providers_detail_proxy_count),
                        value = stringResource(R.string.providers_proxy_count, provider.proxyCount),
                    )
                } else {
                    DetailRow(
                        label = stringResource(R.string.providers_detail_rule_count),
                        value = stringResource(R.string.providers_rule_count, provider.ruleCount),
                    )
                }
                if (provider.hasValidUpdatedAt()) {
                    DetailRow(
                        label = stringResource(R.string.providers_detail_updated),
                        value = provider.updatedAt,
                    )
                }
                if (provider.vehicleType == M2mVehicleType.HTTP && provider.subscriptionUrl.isNotBlank()) {
                    HorizontalDivider()
                    DetailRow(
                        label = stringResource(R.string.providers_detail_url),
                        value = provider.subscriptionUrl,
                        mono = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}
