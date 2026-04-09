package info.loveyu.mfca.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.R

enum class DrawerMenuItem(
    val titleResId: Int,
    val icon: ImageVector
) {
    CONFIG(R.string.config_management, Icons.Default.Build),
    SAMPLES(R.string.sample_configs, Icons.Default.Info),
    SETTINGS(R.string.system_settings, Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawerMenu(
    onItemClick: (DrawerMenuItem) -> Unit
) {
    val configuration = LocalConfiguration.current
    val drawerWidth = (configuration.screenWidthDp / 2).dp

    ModalDrawerSheet(modifier = Modifier.width(drawerWidth)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 24.dp)
        ) {
            DrawerMenuItem.entries.forEach { item ->
                NavigationDrawerItem(
                    icon = { Icon(item.icon, contentDescription = null) },
                    label = { Text(stringResource(item.titleResId)) },
                    selected = false,
                    onClick = { onItemClick(item) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }
}
