package info.loveyu.mfca.ui.notification

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.notification.NotifyRecord
import info.loveyu.mfca.util.cache.IconCacheManager

@Composable
fun NotifyIcon(record: NotifyRecord, size: Dp) {
    val context = LocalContext.current
    var iconBitmap by remember(record.iconUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(record.iconUrl) {
        if (!record.iconUrl.isNullOrBlank()) {
            iconBitmap = IconCacheManager.getInstance(context).getIcon(record.iconUrl, null)
        }
    }
    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(size * 0.5f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun formatRawData(rawData: String): String {
    return try {
        val trimmed = rawData.trimStart()
        if (trimmed.startsWith("{")) org.json.JSONObject(rawData).toString(2)
        else if (trimmed.startsWith("[")) org.json.JSONArray(rawData).toString(2)
        else rawData
    } catch (e: Exception) {
        rawData
    }
}
