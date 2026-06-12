@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.test

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.R
import info.loveyu.mfca.test.input_plugin.InputPluginTestActivity
import info.loveyu.mfca.test.m2m.M2mFullTestActivity
import info.loveyu.mfca.test.output_plugin.OutputPluginTestActivity
import info.loveyu.mfca.test.udp2raw.Udp2RawTestActivity
import info.loveyu.mfca.ui.theme.MfcaTheme

class TestHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MfcaTheme { TestHubScreen(onBack = { finish() }) } }
    }
}

private data class TestModule(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val action: (Context) -> Unit,
)

@Composable
private fun TestHubScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val modules =
        listOf(
            TestModule(
                title = stringResource(R.string.test_udp2raw_title),
                description = stringResource(R.string.test_udp2raw_description),
                icon = Icons.Default.NetworkCheck,
                action = { ctx -> ctx.startActivity(Intent(ctx, Udp2RawTestActivity::class.java)) },
            ),
            TestModule(
                title = stringResource(R.string.test_m2m_title),
                description = stringResource(R.string.test_m2m_description),
                icon = Icons.Default.Language,
                action = { ctx -> ctx.startActivity(Intent(ctx, M2mFullTestActivity::class.java)) },
            ),
            TestModule(
                title = stringResource(R.string.test_m2m_vpn_title),
                description = stringResource(R.string.test_m2m_vpn_description),
                icon = Icons.Default.VpnLock,
                action = { ctx -> ctx.startActivity(Intent(ctx, M2mFullTestActivity::class.java)) },
            ),
            TestModule(
                title = "Input Plugin",
                description = "Test Input plugin slots (mock)",
                icon = Icons.Default.Extension,
                action = { ctx -> ctx.startActivity(Intent(ctx, InputPluginTestActivity::class.java)) },
            ),
            TestModule(
                title = "Output Plugin",
                description = "Test Output plugin slots (mock)",
                icon = Icons.Default.Extension,
                action = { ctx -> ctx.startActivity(Intent(ctx, OutputPluginTestActivity::class.java)) },
            ),
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Science,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.test_hub_title))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = padding.calculateBottomPadding() + 12.dp,
                    start = 16.dp,
                    end = 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(modules) { module ->
                TestModuleCard(module = module, onClick = { module.action(context) })
            }
        }
    }
}

@Composable
private fun TestModuleCard(module: TestModule, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = module.icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = module.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = module.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
