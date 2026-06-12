package info.loveyu.mfca.m2m.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.loveyu.mfca.R
import info.loveyu.mfca.m2m.core.M2mManager
import info.loveyu.mfca.m2m.core.M2mStateStore
import info.loveyu.mfca.m2m.models.M2mLogLevel
import info.loveyu.mfca.m2m.models.M2mRuleMode
import info.loveyu.mfca.ui.theme.MfcaTheme

class M2mCandidateSettingsActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_CANDIDATE_NAME = "candidate_name"

        fun intent(context: Context, candidateName: String): Intent =
            Intent(context, M2mCandidateSettingsActivity::class.java)
                .putExtra(EXTRA_CANDIDATE_NAME, candidateName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val candidateName = intent.getStringExtra(EXTRA_CANDIDATE_NAME) ?: run { finish(); return }
        val store = M2mStateStore(this)

        setContent {
            MfcaTheme {
                VpnCandidateSettingsScreen(
                    candidateName = candidateName,
                    initialPort = store.getLocalPort(candidateName),
                    initialRuleMode = store.getRuleMode(candidateName),
                    initialLogLevel = store.getLogLevel(candidateName),
                    initialUdpRelay = store.getUdpRelay(candidateName),
                    initialIpv6 = store.getIpv6(candidateName),
                    initialDnsHijack = store.getDnsHijack(candidateName),
                    onSave = { port, ruleMode, logLevel, udpRelay, ipv6, dnsHijack ->
                        M2mManager.setOverridePort(candidateName, port)
                        M2mManager.setOverrideRuleMode(candidateName, ruleMode)
                        M2mManager.setOverrideLogLevel(candidateName, logLevel)
                        M2mManager.setUdpRelay(candidateName, udpRelay)
                        M2mManager.setIpv6(candidateName, ipv6)
                        M2mManager.setDnsHijack(candidateName, dnsHijack)
                        finish()
                    },
                    onBack = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VpnCandidateSettingsScreen(
    candidateName: String,
    initialPort: Int?,
    initialRuleMode: M2mRuleMode?,
    initialLogLevel: M2mLogLevel?,
    initialUdpRelay: Boolean,
    initialIpv6: Boolean,
    initialDnsHijack: Boolean,
    onSave: (Int?, M2mRuleMode?, M2mLogLevel?, Boolean, Boolean, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var portText by remember { mutableStateOf(initialPort?.toString() ?: "") }
    var ruleMode by remember { mutableStateOf(initialRuleMode) }
    var logLevel by remember { mutableStateOf(initialLogLevel) }
    var udpRelay by remember { mutableStateOf(initialUdpRelay) }
    var ipv6 by remember { mutableStateOf(initialIpv6) }
    var dnsHijack by remember { mutableStateOf(initialDnsHijack) }
    var portError by remember { mutableStateOf(false) }

    fun saveAndFinish() {
        val port =
            if (portText.isBlank()) {
                null
            } else {
                val parsed = portText.trim().toIntOrNull()
                if (parsed == null || parsed < 1024 || parsed > 65535) {
                    portError = true
                    return
                }
                parsed
            }
        portError = false
        onSave(port, ruleMode, logLevel, udpRelay, ipv6, dnsHijack)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vpn_candidate_settings_title, candidateName)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(onClick = { saveAndFinish() }) {
                        Text(stringResource(R.string.vpn_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.vpn_settings_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Port override
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it; portError = false },
                label = { Text(stringResource(R.string.vpn_port_override)) },
                placeholder = { Text(stringResource(R.string.vpn_port_hint)) },
                isError = portError,
                supportingText =
                    if (portError) {
                        { Text(stringResource(R.string.vpn_port_invalid), color = MaterialTheme.colorScheme.error) }
                    } else {
                        null
                    },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            )

            // Rule mode dropdown
            OverrideDropdown(
                label = stringResource(R.string.vpn_rule_mode),
                selected = ruleMode?.name,
                options = M2mRuleMode.entries.map { it.name },
                onSelect = { selected -> ruleMode = M2mRuleMode.entries.firstOrNull { it.name == selected } },
            )

            // Log level dropdown
            OverrideDropdown(
                label = stringResource(R.string.vpn_log_level),
                selected = logLevel?.name,
                options = M2mLogLevel.entries.map { it.name },
                onSelect = { selected -> logLevel = M2mLogLevel.entries.firstOrNull { it.name == selected } },
            )

            // UDP relay toggle
            SettingToggle(
                label = stringResource(R.string.vpn_udp_relay),
                description = stringResource(R.string.vpn_udp_relay_desc),
                checked = udpRelay,
                onCheckedChange = { udpRelay = it },
            )

            // IPv6 leak protection toggle
            SettingToggle(
                label = stringResource(R.string.vpn_ipv6_leak_protection),
                description = stringResource(R.string.vpn_ipv6_leak_protection_desc),
                checked = ipv6,
                onCheckedChange = { ipv6 = it },
            )

            // DNS hijack toggle
            SettingToggle(
                label = stringResource(R.string.vpn_dns_hijack),
                description = stringResource(R.string.vpn_dns_hijack_desc),
                checked = dnsHijack,
                onCheckedChange = { dnsHijack = it },
            )
        }
    }
}

@Composable
private fun SettingToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverrideDropdown(
    label: String,
    selected: String?,
    options: List<String>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(R.string.vpn_override_default)

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected ?: defaultLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(defaultLabel) },
                onClick = { onSelect(null); expanded = false },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}
