@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.ui.clipboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Password
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
@Composable
fun ClipboardHistoryTopBar(
    onCleanByTime: () -> Unit = {},
    onCleanPasswords: () -> Unit = {},
    onCleanVerificationCodes: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("剪贴板历史") },
        actions = {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = null)
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("按时间清理") },
                    onClick = { showMenu = false; onCleanByTime() },
                    leadingIcon = {
                        Icon(Icons.Default.CleaningServices, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text("清理密码") },
                    onClick = { showMenu = false; onCleanPasswords() },
                    leadingIcon = {
                        Icon(Icons.Default.Password, contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text("清理验证码") },
                    onClick = { showMenu = false; onCleanVerificationCodes() },
                    leadingIcon = {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null)
                    },
                )
            }
        },
    )
}
