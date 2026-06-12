package info.loveyu.mfca.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

enum class ComponentType {
    LINK, HTTP_INPUT, LINK_INPUT, UDP2RAW, RULE, OUTPUT, QUEUE
}

data class UpstreamLinkStatus(
    val linkId: String,
    val isConnected: Boolean,
    val isNetworkEnabled: Boolean,
    val typeLabel: String
)

data class ComponentStatus(
    val id: String,
    val name: String,
    val type: ComponentType,
    val isEnabled: Boolean,
    val isRunning: Boolean,
    val notEnabledReason: String? = null,
    val details: String = "",
    val error: String? = null,
    val copyableLinks: List<String> = emptyList(),
    val upstreamLinks: List<UpstreamLinkStatus> = emptyList()
) {
    val isLink: Boolean get() = type == ComponentType.LINK
    val isInput: Boolean
        get() = type == ComponentType.HTTP_INPUT || type == ComponentType.LINK_INPUT
}

fun getComponentTypeOrder(type: ComponentType): Int {
    return when (type) {
        ComponentType.LINK -> 0
        ComponentType.HTTP_INPUT -> 1
        ComponentType.LINK_INPUT -> 2
        ComponentType.UDP2RAW -> 3
        ComponentType.RULE -> 4
        ComponentType.OUTPUT -> 5
        ComponentType.QUEUE -> 6
    }
}

fun getComponentIcon(type: ComponentType): ImageVector {
    return when (type) {
        ComponentType.LINK -> Icons.Default.Star
        ComponentType.HTTP_INPUT -> Icons.Default.PlayArrow
        ComponentType.LINK_INPUT -> Icons.Default.PlayArrow
        ComponentType.UDP2RAW -> Icons.Default.PlayArrow
        ComponentType.RULE -> Icons.Default.Build
        ComponentType.OUTPUT -> Icons.AutoMirrored.Filled.Send
        ComponentType.QUEUE -> Icons.Default.Settings
    }
}

fun getComponentTypeName(type: ComponentType): String {
    return when (type) {
        ComponentType.LINK -> "Link"
        ComponentType.HTTP_INPUT -> "HTTP Input"
        ComponentType.LINK_INPUT -> "Link Input"
        ComponentType.UDP2RAW -> "UDP2RAW"
        ComponentType.RULE -> "Rule"
        ComponentType.OUTPUT -> "Output"
        ComponentType.QUEUE -> "Queue"
    }
}
