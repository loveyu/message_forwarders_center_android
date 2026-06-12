@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.notification.TimeRange

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

@Composable
fun NotifyHistoryScreen(
    onBack: () -> Unit,
    highlightNotifyId: Int? = null,
    modifier: Modifier = Modifier,
    refreshTrigger: Int = 0
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    NotifyHistoryContent(
        onBack = onBack,
        highlightNotifyId = highlightNotifyId,
        drawerState = drawerState,
        refreshTrigger = refreshTrigger
    )
}

@Composable
fun FilterDrawerContent(
    sourceRuleOptions: List<String>,
    selectedSourceRule: String?,
    onSourceRuleSelect: (String?) -> Unit,
    outputNameOptions: List<String>,
    selectedOutputName: String?,
    onOutputNameSelect: (String?) -> Unit,
    selectedTimeRange: TimeRange?,
    onTimeRangeSelect: (TimeRange) -> Unit,
    showTimeFilter: Boolean,
    onToggleTimeFilter: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.fillMaxWidth(0.75f),
        windowInsets = WindowInsets(0)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "筛选设置",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = "来源规则",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            FilterOptionList(
                options = sourceRuleOptions,
                selected = selectedSourceRule,
                onSelect = onSourceRuleSelect
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "输出名称",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            FilterOptionList(
                options = outputNameOptions,
                selected = selectedOutputName,
                onSelect = onOutputNameSelect
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("显示时间筛选")
                Switch(
                    checked = showTimeFilter,
                    onCheckedChange = onToggleTimeFilter
                )
            }

            if (showTimeFilter) {
                Spacer(modifier = Modifier.height(8.dp))
                TimeRange.entries.forEach { range ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedTimeRange == range,
                                onClick = { onTimeRangeSelect(range) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selectedTimeRange == range,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(range.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("清空当前筛选记录")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun ClearNotifyHistoryDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清空筛选记录") },
        text = { Text("确定要清空当前筛选条件下的所有通知记录吗？此操作不可撤销。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("清空", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
