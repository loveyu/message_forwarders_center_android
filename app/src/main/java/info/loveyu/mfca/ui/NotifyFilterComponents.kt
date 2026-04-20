package info.loveyu.mfca.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun NotifySearchBar(keyword: String, onKeywordChange: (String) -> Unit) {
    OutlinedTextField(
        value = keyword,
        onValueChange = onKeywordChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(48.dp),
        placeholder = {
            Text("搜索标题、内容...", style = MaterialTheme.typography.bodyMedium)
        },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
fun TimeRangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        modifier = Modifier.height(32.dp)
    )
}

@Composable
fun FilterOptionList(
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FilterOptionItem(label = "全部", isSelected = selected == null) { onSelect(null) }
        options.forEach { option ->
            FilterOptionItem(
                label = option,
                isSelected = selected == option
            ) { onSelect(option) }
        }
    }
}

@Composable
fun FilterOptionItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun FilterIndicatorChip(label: String, onClear: () -> Unit) {
    AssistChip(
        onClick = onClear,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier.height(28.dp)
    )
}
