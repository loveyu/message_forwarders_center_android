@file:OptIn(ExperimentalMaterial3Api::class)

package info.loveyu.mfca.test.output_plugin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.loveyu.mfca.config.models.PluginMode
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutputPluginTestScreen(
    onBack: () -> Unit,
    engine: OutputPluginTestEngine,
) {
    val scope = rememberCoroutineScope()
    var selectedScenarioIndex by remember { mutableIntStateOf(0) }
    var selectedMode by remember { mutableStateOf(PluginMode.SERIAL) }
    var isRunning by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<OutputPluginTestRunResult?>(null) }
    var expandedSlot by remember { mutableIntStateOf(-1) }
    val scenarios = remember { OutputPluginTestEngine.presets() }
    var scenarioDropdownExpanded by remember { mutableStateOf(false) }
    val logLines = remember { mutableStateListOf<String>() }
    val logListState = rememberLazyListState()

    fun addLog(text: String) {
        logLines.add(text)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Output Plugin Test") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            item {
                Text("Slot Capabilities", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (slot in 0 until 10) {
                        val caps = engine.getSlotCaps(slot)
                        val label = "S$slot [${engine.getSlotCapabilityLabel(caps)}]"
                        FilterChip(
                            selected = false,
                            onClick = {},
                            label = { Text(label, fontSize = 10.sp) },
                            leadingIcon = {
                                Box(
                                    Modifier.size(8.dp).background(
                                        if (engine.isSlotLoaded(slot)) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
                                        CircleShape,
                                    )
                                )
                            },
                        )
                    }
                }
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Test Configuration", style = MaterialTheme.typography.titleSmall)

                        ExposedDropdownMenuBox(
                            expanded = scenarioDropdownExpanded,
                            onExpandedChange = { scenarioDropdownExpanded = !scenarioDropdownExpanded },
                        ) {
                            OutlinedTextField(
                                value = scenarios.getOrNull(selectedScenarioIndex)?.name ?: "",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Scenario") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = scenarioDropdownExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                singleLine = true,
                            )
                            ExposedDropdownMenu(
                                expanded = scenarioDropdownExpanded,
                                onDismissRequest = { scenarioDropdownExpanded = false },
                            ) {
                                scenarios.forEachIndexed { index, scenario ->
                                    DropdownMenuItem(
                                        text = { Text(scenario.name) },
                                        onClick = {
                                            selectedScenarioIndex = index
                                            scenarioDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = selectedMode == PluginMode.SERIAL,
                                onClick = { selectedMode = PluginMode.SERIAL },
                                label = { Text("SERIAL") },
                            )
                            FilterChip(
                                selected = selectedMode == PluginMode.PARALLEL,
                                onClick = { selectedMode = PluginMode.PARALLEL },
                                label = { Text("PARALLEL") },
                            )
                        }

                        Button(
                            onClick = {
                                isRunning = true
                                lastResult = null
                                val scenario = scenarios[selectedScenarioIndex]
                                scope.launch {
                                    val result = withContext(Dispatchers.Default) {
                                        engine.runOutputTest(scenario, selectedMode)
                                    }
                                    lastResult = result
                                    addLog("${scenario.name} [${selectedMode.name}] — ${if (result.modified) "MODIFIED" else "PASS"}")
                                    addLog("  Data: ${if (result.dataChanged) "changed" else "unchanged"}")
                                    addLog("  Headers: ${if (result.headersChanged) "changed" else "unchanged"}")
                                    result.slotExecutions.forEach { exec ->
                                        addLog("  S${exec.slot}: ${exec.statsAfter.callCount}calls ${exec.statsAfter.totalTimeMs}ms ${if (exec.outputJson == null) "ERR" else "OK"}")
                                    }
                                    isRunning = false
                                }
                            },
                            enabled = !isRunning,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (isRunning) "Running..." else "Run Test")
                        }
                    }
                }
            }

            lastResult?.let { result ->
                item { Text("Pipeline Result", style = MaterialTheme.typography.titleSmall) }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (result.modified) Color(0xFFE8F5E9) else Color(0xFFFFF8E1),
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(12.dp).background(
                                        if (result.modified) Color(0xFF4CAF50) else Color(0xFFFFC107),
                                        CircleShape,
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (result.modified) "Output data/headers modified" else "All plugins passed (no change)",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Data changed: ${result.dataChanged}", fontSize = 10.sp)
                            Text("Headers changed: ${result.headersChanged}", fontSize = 10.sp)
                            if (result.dataChanged) {
                                Spacer(Modifier.height(4.dp))
                                Text("New data: ${result.resultDataPreview}",
                                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }
                            if (result.headersChanged) {
                                Spacer(Modifier.height(4.dp))
                                Text("New headers: ${result.resultHeaders}",
                                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }
                        }
                    }
                }

                item {
                    Text("Slot Execution Details (${result.slotExecutions.size} active)", style = MaterialTheme.typography.titleSmall)
                }
                items(result.slotExecutions) { exec ->
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth().clickable {
                            expandedSlot = if (expandedSlot == exec.slot) -1 else exec.slot
                        },
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("S${exec.slot}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(Modifier.width(8.dp))
                                Text("[${engine.getSlotCapabilityLabel(exec.caps)}]", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (exec.outputJson != null) "output=ok" else "output=null",
                                    fontSize = 10.sp,
                                    color = if (exec.outputJson != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.weight(1f))
                                Text("${exec.statsAfter.callCount}call ${exec.statsAfter.totalTimeMs}ms",
                                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(
                                    if (expandedSlot == exec.slot) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null, modifier = Modifier.size(16.dp),
                                )
                            }
                            AnimatedVisibility(visible = expandedSlot == exec.slot) {
                                Column(modifier = Modifier.padding(top = 8.dp)) {
                                    HorizontalDivider()
                                    Spacer(Modifier.height(8.dp))
                                    Text("Input JSON:", fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                                    Text(
                                        formatJson(exec.inputJson),
                                        fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                                        maxLines = 30, overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    if (exec.outputJson != null) {
                                        Text("Output JSON:", fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                                        Text(
                                            formatJson(exec.outputJson),
                                            fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                                            maxLines = 30, overflow = TextOverflow.Ellipsis,
                                        )
                                    } else {
                                        Text("Output: null (plugin returned error)", color = MaterialTheme.colorScheme.error, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("Execution Log", style = MaterialTheme.typography.titleSmall)
            }
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                ) {
                    LazyColumn(state = logListState) {
                        items(logLines.toList()) { line ->
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun formatJson(json: String): String {
    return try {
        org.json.JSONObject(json).toString(2)
    } catch (_: Exception) {
        try {
            org.json.JSONArray(json).toString(2)
        } catch (_: Exception) {
            json
        }
    }
}
