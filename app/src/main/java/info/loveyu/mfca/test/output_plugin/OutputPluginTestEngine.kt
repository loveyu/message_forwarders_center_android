package info.loveyu.mfca.test.output_plugin

import android.content.Context
import info.loveyu.mfca.config.models.OutputPluginConfig
import info.loveyu.mfca.config.models.PluginMode
import info.loveyu.mfca.config.models.PluginModeConfig
import info.loveyu.mfca.output.plugin.OutputPluginDispatcher
import info.loveyu.mfca.plugin.core.PluginBase
import info.loveyu.mfca.plugin.core.PluginEngine
import info.loveyu.mfca.plugin.core.SlotStats

class OutputPluginTestEngine(context: Context) {

    val engine: PluginEngine
    private val allSlots = (0 until 10).toList()
    private val slotCapsArray: IntArray

    init {
        engine = PluginEngine(
            context = context,
            prefix = "OutputTest",
            slotFactory = { slot -> createOutputMockSlot(slot) },
        )
        registerMockSlots(engine, allSlots)
        slotCapsArray = readSlotCaps(engine)
    }

    fun runOutputTest(
        scenario: OutputPluginTestScenario,
        mode: PluginMode = PluginMode.SERIAL,
        slots: List<Int> = allSlots,
    ): OutputPluginTestRunResult {
        clearOutputMockRecords()
        val config = OutputPluginConfig(
            enabled = true,
            front = PluginModeConfig(mode = mode, slots = slots),
        )
        val dispatcher = OutputPluginDispatcher(engine, config)
        val (resultData, resultHeaders) = dispatcher.intercept(
            outputName = scenario.outputName,
            outputTypeName = scenario.outputType,
            data = scenario.data,
            headers = scenario.headers,
            ruleName = scenario.ruleName,
            source = scenario.source,
        )
        val slotExecutions = (0 until 10).mapNotNull { slot ->
            val stats = getOutputMockStats(slot)
            if (stats.callCount == 0) return@mapNotNull null
            SlotExecution(
                slot = slot,
                caps = getSlotCaps(slot),
                inputJson = stats.lastInput,
                outputJson = stats.lastOutput,
                statsAfter = engine.slotStats[slot].copy(),
            )
        }
        val dataChanged = !resultData.contentEquals(scenario.data)
        val headersChanged = resultHeaders != scenario.headers
        return OutputPluginTestRunResult(
            modified = dataChanged || headersChanged,
            resultDataPreview = previewData(resultData),
            resultHeaders = resultHeaders,
            dataChanged = dataChanged,
            headersChanged = headersChanged,
            slotExecutions = slotExecutions,
        )
    }

    fun isSlotLoaded(slot: Int): Boolean = engine.isLoaded(slot)

    fun getSlotStats(slot: Int): SlotStats = engine.slotStats[slot]

    fun getSlotCaps(slot: Int): Int = if (slot in slotCapsArray.indices) slotCapsArray[slot] else 0

    fun getSlotCapabilityLabel(caps: Int): String {
        val parts = mutableListOf<String>()
        if (caps and PluginBase.CAP_FRONT != 0) parts.add("F")
        if (caps and PluginBase.CAP_REAR != 0) parts.add("R")
        return parts.joinToString("|").ifEmpty { "-" }
    }

    companion object {
        private fun readSlotCaps(engine: PluginEngine): IntArray {
            return try {
                val capsField = PluginEngine::class.java.getDeclaredField("slotCaps")
                capsField.isAccessible = true
                (capsField.get(engine) as IntArray).copyOf()
            } catch (_: Exception) {
                IntArray(10)
            }
        }

        private fun registerMockSlots(engine: PluginEngine, slots: List<Int>) {
            try {
                val pluginsField = PluginEngine::class.java.getDeclaredField("plugins")
                pluginsField.isAccessible = true
                val plugins = pluginsField.get(engine) as Array<PluginBase?>
                val capsField = PluginEngine::class.java.getDeclaredField("slotCaps")
                capsField.isAccessible = true
                val slotCaps = capsField.get(engine) as IntArray
                for (slot in slots.distinct()) {
                    if (slot in 0 until 10) {
                        val mock = createOutputMockSlot(slot)
                        plugins[slot] = mock
                        slotCaps[slot] = mock.getCapabilities()
                    }
                }
            } catch (_: Exception) {
            }
        }

        private fun previewData(data: ByteArray): String {
            if (data.isEmpty()) return "(empty)"
            val preview = String(data, Charsets.UTF_8).take(200)
            return if (data.size > 200) "$preview..." else preview
        }

        fun presets(): List<OutputPluginTestScenario> {
            return listOf(
                OutputPluginTestScenario(
                    name = "Simple Text",
                    data = "Hello Output".toByteArray(Charsets.UTF_8),
                ),
                OutputPluginTestScenario(
                    name = "JSON Message",
                    data = """{"event":"test","value":42}""".toByteArray(Charsets.UTF_8),
                    headers = mapOf("content-type" to "application/json"),
                ),
                OutputPluginTestScenario(
                    name = "With Headers + Metadata",
                    data = "data with context".toByteArray(Charsets.UTF_8),
                    headers = mapOf("content-type" to "text/plain", "x-request-id" to "req-001"),
                    outputName = "webhook_output",
                    outputType = "http",
                    ruleName = "notify_rule",
                    source = "http_input",
                ),
                OutputPluginTestScenario(
                    name = "Large Output",
                    data = "B".repeat(10000).toByteArray(Charsets.UTF_8),
                ),
                OutputPluginTestScenario(
                    name = "Empty Output",
                    data = ByteArray(0),
                ),
                OutputPluginTestScenario(
                    name = "MQTT Output",
                    data = "mqtt payload".toByteArray(Charsets.UTF_8),
                    outputName = "mqtt_bridge",
                    outputType = "mqtt",
                ),
                OutputPluginTestScenario(
                    name = "Binary Output",
                    data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
                ),
            )
        }
    }
}

data class SlotExecution(
    val slot: Int,
    val caps: Int,
    val inputJson: String,
    val outputJson: String?,
    val statsAfter: SlotStats,
)

data class OutputPluginTestScenario(
    val name: String,
    val data: ByteArray,
    val headers: Map<String, String> = emptyMap(),
    val outputName: String = "test_output",
    val outputType: String = "http",
    val ruleName: String = "test_rule",
    val source: String = "test_source",
)

data class OutputPluginTestRunResult(
    val modified: Boolean,
    val resultDataPreview: String = "",
    val resultHeaders: Map<String, String> = emptyMap(),
    val dataChanged: Boolean = false,
    val headersChanged: Boolean = false,
    val slotExecutions: List<SlotExecution> = emptyList(),
) {
    val summary: String
        get() = buildString {
            if (modified) {
                appendLine("Data modified: $dataChanged")
                appendLine("Headers modified: $headersChanged")
                appendLine("Result data: $resultDataPreview")
                appendLine("Result headers: $resultHeaders")
            } else {
                appendLine("No modification (all pass)")
            }
        }
}
