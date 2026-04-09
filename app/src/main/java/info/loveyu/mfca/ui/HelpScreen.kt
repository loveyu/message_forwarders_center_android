package info.loveyu.mfca.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.IOException

data class SampleFile(
    val name: String,
    val description: String,
    val content: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedFile by remember { mutableStateOf<SampleFile?>(null) }
    var sampleFiles by remember { mutableStateOf<List<SampleFile>>(emptyList()) }

    LaunchedEffect(Unit) {
        sampleFiles = loadSampleFiles(context)
    }

    if (selectedFile != null) {
        SampleDetailScreen(
            sampleFile = selectedFile!!,
            onBack = { selectedFile = null }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("配置示例") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sampleFiles) { file ->
                    SampleFileCard(
                        sampleFile = file,
                        onClick = { selectedFile = file }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleFileCard(
    sampleFile: SampleFile,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = sampleFile.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = sampleFile.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleDetailScreen(
    sampleFile: SampleFile,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sampleFile.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = sampleFile.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = sampleFile.content,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun loadSampleFiles(context: Context): List<SampleFile> {
    val sampleList = listOf(
        SampleFileInfo(
            fileName = "01_basic_mqtt.yaml",
            description = "MQTT 基础连接 - 基本链接配置、重连、TLS 支持"
        ),
        SampleFileInfo(
            fileName = "02_websocket_link.yaml",
            description = "WebSocket 连接 - WS/WSS 配置"
        ),
        SampleFileInfo(
            fileName = "03_tcp_link.yaml",
            description = "TCP 连接 - Socket 配置"
        ),
        SampleFileInfo(
            fileName = "04_network_conditions.yaml",
            description = "网络条件控制 - WiFi SSID/BSSID、IP 段限制"
        ),
        SampleFileInfo(
            fileName = "05_http_input.yaml",
            description = "HTTP 输入 - NanoHTTPD、认证方式"
        ),
        SampleFileInfo(
            fileName = "06_link_input_output.yaml",
            description = "Link 输入输出 - 订阅/发布示例"
        ),
        SampleFileInfo(
            fileName = "07_memory_queue.yaml",
            description = "内存队列 - 高性能临时缓冲"
        ),
        SampleFileInfo(
            fileName = "08_sqlite_queue.yaml",
            description = "SQLite 持久化队列 - 重试、退避、清理"
        ),
        SampleFileInfo(
            fileName = "09_outputs.yaml",
            description = "输出模块 - HTTP/Link/Internal 输出"
        ),
        SampleFileInfo(
            fileName = "10_rules.yaml",
            description = "规则引擎 - 提取、过滤、检测"
        ),
        SampleFileInfo(
            fileName = "11_full_demo.yaml",
            description = "完整演示 - 智能家居场景"
        )
    )

    return sampleList.mapNotNull { info ->
        try {
            val content = context.assets.open("samples/${info.fileName}")
                .bufferedReader()
                .use { it.readText() }
            SampleFile(
                name = info.fileName,
                description = info.description,
                content = content
            )
        } catch (e: IOException) {
            null
        }
    }
}

private data class SampleFileInfo(
    val fileName: String,
    val description: String
)
