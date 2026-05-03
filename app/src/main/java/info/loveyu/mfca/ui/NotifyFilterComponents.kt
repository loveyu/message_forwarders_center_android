package info.loveyu.mfca.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

/**
 * Floating search bar with FAB trigger.
 * Shows a FAB in the bottom-end; tapping it reveals a bottom search input with animation.
 * @param visible Whether the search bar is currently shown.
 * @param keyword Current search keyword.
 * @param onKeywordChange Callback when keyword changes.
 * @param onRequestShow Callback to request showing the search bar.
 * @param onRequestHide Callback to request hiding the search bar.
 */
@Composable
fun FloatingSearchBar(
    visible: Boolean,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onRequestShow: () -> Unit,
    onRequestHide: () -> Unit,
    placeholder: String = "搜索..."
) {
    val focusRequester = remember { FocusRequester() }

    // FAB - hidden when search bar is visible
    AnimatedVisibility(
        visible = !visible,
        exit = androidx.compose.animation.fadeOut()
    ) {
        FloatingActionButton(
            onClick = onRequestShow,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = "搜索",
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }

    // Search bar at bottom
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 4.dp,
            tonalElevation = 2.dp
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium)
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                },
                trailingIcon = {
                    Surface(
                        onClick = onRequestHide,
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭搜索",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyLarge
            )
        }
    }

    // Auto-focus when shown
    androidx.compose.runtime.LaunchedEffect(visible) {
        if (visible) {
            focusRequester.requestFocus()
        }
    }
}

@Composable
fun TimeRangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
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
