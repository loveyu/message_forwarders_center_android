@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.test.m2m

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SettingsSheetContent(
    coreUrl: String,
    onCoreUrlChange: (String) -> Unit,
    proxyUrl: String,
    onProxyUrlChange: (String) -> Unit,
    configUrl: String,
    onConfigUrlChange: (String) -> Unit,
    mixedPort: String,
    onMixedPortChange: (String) -> Unit,
    testUrl: String,
    onTestUrlChange: (String) -> Unit,
    insecure: Boolean,
    onInsecureChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("测试设置", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = coreUrl,
            onValueChange = onCoreUrlChange,
            label = { Text("核心地址（.so/.zip/.gz 或 data:// 等）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = proxyUrl,
            onValueChange = onProxyUrlChange,
            label = { Text("下载代理（同时用于配置文件下载）") },
            placeholder = { Text("如 socks5://127.0.0.1:1080") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = configUrl,
            onValueChange = onConfigUrlChange,
            label = { Text("配置文件下载地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = mixedPort,
            onValueChange = onMixedPortChange,
            label = { Text("覆盖的混合端口") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = testUrl,
            onValueChange = onTestUrlChange,
            label = { Text("测试访问地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("跳过 SSL 证书校验", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = insecure, onCheckedChange = onInsecureChange)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onConfirm) { Text("确定") }
        }
    }
}

@Composable
fun M2mStepRow(step: M2mTestStep) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (step.status) {
            M2mStepStatus.IDLE ->
                Box(
                    Modifier
                        .size(12.dp)
                        .background(Color.Gray.copy(alpha = 0.4f), CircleShape)
                )
            M2mStepStatus.RUNNING ->
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp
                )
            M2mStepStatus.SUCCESS ->
                Box(Modifier.size(12.dp).background(Color(0xFF4CAF50), CircleShape))
            M2mStepStatus.FAILED ->
                Box(
                    Modifier
                        .size(12.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = step.label,
            style = MaterialTheme.typography.bodySmall,
            color =
                when (step.status) {
                    M2mStepStatus.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
        )
    }
}
