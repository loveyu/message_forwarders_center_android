package info.loveyu.mfca.test.input_plugin

import android.content.Context
import info.loveyu.mfca.config.models.InputPluginConfig
import info.loveyu.mfca.config.models.PluginMode
import info.loveyu.mfca.config.models.PluginModeConfig
import info.loveyu.mfca.input.InputMessage
import info.loveyu.mfca.input.plugin.InputPluginDispatcher
import info.loveyu.mfca.plugin.core.PluginBase
import info.loveyu.mfca.plugin.core.PluginEngine
import info.loveyu.mfca.plugin.core.SlotStats

class InputPluginTestEngine(context: Context) {

    val engine: PluginEngine
    private val allSlots = (0 until 10).toList()
    private val slotCapsArray: IntArray

    init {
        engine = PluginEngine(
            context = context,
            prefix = "InputTest",
            slotFactory = { slot -> createInputMockSlot(slot) },
        )
        registerMockSlots(engine, allSlots)
        slotCapsArray = readSlotCaps(engine)
    }

    fun runFrontTest(
        scenario: InputPluginTestScenario,
        mode: PluginMode = PluginMode.SERIAL,
        slots: List<Int> = allSlots,
    ): InputPluginTestRunResult {
        clearInputMockRecords()
        val config = InputPluginConfig(
            enabled = true,
            front = PluginModeConfig(mode = mode, slots = slots),
        )
        val dispatcher = InputPluginDispatcher(engine, config)
        val message = InputMessage(
            source = scenario.source,
            data = scenario.data,
            headers = scenario.headers,
            metadata = scenario.metadata,
        )
        val result = dispatcher.interceptFront(message)
        return buildResult(result, slotResults = null) { msg ->
            buildString {
                appendLine("source=${msg.source}")
                appendLine("data=${previewData(msg.data)}")
                appendLine("headers=${msg.headers}")
                appendLine("metadata=${msg.metadata}")
            }
        }
    }

    fun runRearTest(
        scenario: InputPluginTestScenario,
        mode: PluginMode = PluginMode.SERIAL,
        slots: List<Int> = allSlots,
    ): InputPluginTestRunResult {
        clearInputMockRecords()
        val config = InputPluginConfig(
            enabled = true,
            rear = PluginModeConfig(mode = mode, slots = slots),
        )
        val dispatcher = InputPluginDispatcher(engine, config)
        val result = dispatcher.interceptRear(
            sourceName = scenario.source,
            uri = scenario.rearUri,
            method = scenario.rearMethod,
            statusCode = scenario.rearStatusCode,
            responseBody = scenario.rearResponseBody,
            responseHeaders = scenario.rearResponseHeaders,
        )
        return buildResult(result, slotResults = null) { res ->
            buildString {
                appendLine("statusCode=${res.statusCode}")
                appendLine("body=${res.responseBody.take(200)}")
                appendLine("headers=${res.responseHeaders}")
            }
        }
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

    private fun <T> buildResult(
        result: T?,
        slotResults: Nothing?,
        summaryBuilder: (T) -> String,
    ): InputPluginTestRunResult {
        val slotExecutions = (0 until 10).mapNotNull { slot ->
            val stats = getInputMockStats(slot)
            if (stats.callCount == 0) return@mapNotNull null
            SlotExecution(
                slot = slot,
                caps = getSlotCaps(slot),
                inputJson = stats.lastInput,
                outputJson = stats.lastOutput,
                statsAfter = engine.slotStats[slot].copy(),
            )
        }
        return InputPluginTestRunResult(
            success = result != null,
            resultMessage = result?.let { summaryBuilder(it) },
            slotExecutions = slotExecutions,
        )
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
                        val mock = createInputMockSlot(slot)
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

        fun presets(): List<InputPluginTestScenario> {
            return listOf(
                InputPluginTestScenario(
                    name = "Simple Text",
                    data = "Hello World".toByteArray(Charsets.UTF_8),
                ),
                InputPluginTestScenario(
                    name = "JSON Payload",
                    data = """{"temperature":25.5,"humidity":60,"device":"sensor-01"}""".toByteArray(Charsets.UTF_8),
                    headers = mapOf("content-type" to "application/json"),
                ),
                InputPluginTestScenario(
                    name = "With Headers",
                    data = "message with headers".toByteArray(Charsets.UTF_8),
                    headers = mapOf(
                        "content-type" to "text/plain",
                        "x-custom" to "test-value",
                    ),
                    metadata = mapOf("env" to "test"),
                ),
                InputPluginTestScenario(
                    name = "Empty Data",
                    data = ByteArray(0),
                ),
                InputPluginTestScenario(
                    name = "Large Payload",
                    data = "A".repeat(10000).toByteArray(Charsets.UTF_8),
                ),
                InputPluginTestScenario(
                    name = "Rear Test (201)",
                    data = "request".toByteArray(Charsets.UTF_8),
                    rearMethod = "PUT",
                    rearUri = "/api/data",
                    rearStatusCode = 201,
                    rearResponseBody = "created successfully",
                    rearResponseHeaders = mapOf("content-type" to "text/plain", "x-request-id" to "abc-123"),
                ),
        InputPluginTestScenario(
            name = "Binary Data",
            data = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0xFE.toByte(), 0x7F),
        ),
                InputPluginTestScenario(
                    name = "Special Chars",
                    data = "Hello 世界 🌍! \n\t\"escaped\"".toByteArray(Charsets.UTF_8),
                ),
            )
        }
    }
}

data class InputPluginTestScenario(
    val name: String,
    val data: ByteArray,
    val headers: Map<String, String> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
    val source: String = "test_input",
    val rearMethod: String = "POST",
    val rearUri: String = "/test",
    val rearStatusCode: Int = 200,
    val rearResponseBody: String = "default response body",
    val rearResponseHeaders: Map<String, String> = mapOf("content-type" to "text/plain"),
)

data class InputPluginTestRunResult(
    val success: Boolean,
    val resultMessage: String? = null,
    val slotExecutions: List<SlotExecution> = emptyList(),
)

data class SlotExecution(
    val slot: Int,
    val caps: Int,
    val inputJson: String,
    val outputJson: String?,
    val statsAfter: SlotStats,
)
