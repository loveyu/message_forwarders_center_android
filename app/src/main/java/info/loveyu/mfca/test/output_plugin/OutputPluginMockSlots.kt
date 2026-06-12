package info.loveyu.mfca.test.output_plugin

import android.util.Base64
import info.loveyu.mfca.plugin.core.PluginBase
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal val outputMockRecords: MutableMap<Int, MutableList<Pair<String, String?>>> = ConcurrentHashMap()

internal fun clearOutputMockRecords() {
    outputMockRecords.clear()
}

internal fun getOutputMockStats(slot: Int): OutputMockSlotStats {
    val records = outputMockRecords[slot]
    if (records == null || records.isEmpty()) return OutputMockSlotStats()
    val last = records.last()
    return OutputMockSlotStats(
        callCount = records.size,
        lastInput = last.first,
        lastOutput = last.second,
    )
}

internal data class OutputMockSlotStats(
    val callCount: Int = 0,
    val lastInput: String = "",
    val lastOutput: String? = null,
)

internal class OutputMockSlot0 : PluginBase("output_test_slot_0") {
    override fun process(inputJson: String): String? {
        val output = JSONObject().apply {
            put("action", "pass")
            put("logs", JSONObject.wrap(listOf(
                JSONObject().apply { put("level", "info"); put("message", "Slot0: output pass-through") }
            )))
        }.toString()
        outputMockRecords.getOrPut(0) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal class OutputMockSlot1 : PluginBase("output_test_slot_1") {
    override fun process(inputJson: String): String? {
        val input = JSONObject(inputJson)
        val b64 = input.optString("dataBase64", "")
        val decoded = if (b64.isNotBlank()) String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) else ""
        val modified = "[OUTPUT_SLOT1] $decoded"
        val encoded = Base64.encodeToString(modified.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val output = JSONObject().apply {
            put("action", "modify")
            put("dataBase64", encoded)
            put("logs", JSONObject.wrap(listOf(
                JSONObject().apply { put("level", "info"); put("message", "Slot1: output - prepended prefix") }
            )))
        }.toString()
        outputMockRecords.getOrPut(1) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal class OutputMockSlot2 : PluginBase("output_test_slot_2") {
    override fun process(inputJson: String): String? {
        val output = JSONObject().apply {
            put("action", "modify")
            put("headers", JSONObject().apply { put("x-output-processed", "slot2") })
            put("logs", JSONObject.wrap(listOf(
                JSONObject().apply { put("level", "info"); put("message", "Slot2: output - added header") }
            )))
        }.toString()
        outputMockRecords.getOrPut(2) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal class OutputMockSlot3 : PluginBase("output_test_slot_3") {
    override fun process(inputJson: String): String? {
        outputMockRecords.getOrPut(3) { mutableListOf() }.add(inputJson to null)
        return null
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal class OutputMockSlot4 : PluginBase("output_test_slot_4") {
    override fun process(inputJson: String): String? {
        val input = JSONObject(inputJson)
        val b64 = input.optString("dataBase64", "")
        val decoded = if (b64.isNotBlank()) String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) else ""
        val modified = "[SLOT4] $decoded [END]"
        val encoded = Base64.encodeToString(modified.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val output = JSONObject().apply {
            put("action", "modify")
            put("dataBase64", encoded)
            put("headers", JSONObject().apply { put("x-slot4", "data+headers") })
            put("logs", JSONObject.wrap(listOf(
                JSONObject().apply { put("level", "info"); put("message", "Slot4: output - modified data + headers") }
            )))
        }.toString()
        outputMockRecords.getOrPut(4) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal class OutputMockSlot5 : PluginBase("output_test_slot_5") {
    override fun process(inputJson: String): String? {
        val output = JSONObject().apply {
            put("action", "pass")
            put("logs", JSONObject.wrap(listOf(
                JSONObject().apply { put("level", "warn"); put("message", "Slot5: output warning, pass-through") }
            )))
        }.toString()
        outputMockRecords.getOrPut(5) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal class OutputMockSlot6 : PluginBase("output_test_slot_6") {
    override fun process(inputJson: String): String? {
        val input = JSONObject(inputJson)
        val b64 = input.optString("dataBase64", "")
        val decoded = if (b64.isNotBlank()) String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) else ""
        val reversed = decoded.reversed()
        val encoded = Base64.encodeToString(reversed.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val output = JSONObject().apply {
            put("action", "modify")
            put("dataBase64", encoded)
            put("logs", JSONObject.wrap(listOf(
                JSONObject().apply { put("level", "info"); put("message", "Slot6: output - reversed content") }
            )))
        }.toString()
        outputMockRecords.getOrPut(6) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal class OutputMockSlot7 : PluginBase("output_test_slot_7") {
    override fun process(inputJson: String): String? {
        val output = JSONObject().apply {
            put("action", "modify")
            put("metadata", JSONObject().apply {
                put("outputSlot7", "active")
                put("processedAt", System.currentTimeMillis().toString())
            })
            put("logs", JSONObject.wrap(listOf(
                JSONObject().apply { put("level", "info"); put("message", "Slot7: output - added metadata") }
            )))
        }.toString()
        outputMockRecords.getOrPut(7) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal class OutputMockSlot8 : PluginBase("output_test_slot_8") {
    override fun process(inputJson: String): String? {
        val output = JSONObject().apply {
            put("action", "pass")
            put("logs", JSONObject.wrap(listOf(
                JSONObject().apply { put("level", "error"); put("message", "Slot8: output error log, still pass") }
            )))
        }.toString()
        outputMockRecords.getOrPut(8) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal class OutputMockSlot9 : PluginBase("output_test_slot_9") {
    override fun process(inputJson: String): String? {
        val input = JSONObject(inputJson)
        val b64 = input.optString("dataBase64", "")
        val decoded = if (b64.isNotBlank()) String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) else ""
        val modified = decoded.uppercase()
        val encoded = Base64.encodeToString(modified.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val output = JSONObject().apply {
            put("action", "modify")
            put("dataBase64", encoded)
            put("headers", JSONObject().apply { put("x-slot9", "uppercased") })
            put("logs", JSONObject.wrap(listOf(
                JSONObject().apply { put("level", "info"); put("message", "Slot9: output - uppercased data + headers") }
            )))
        }.toString()
        outputMockRecords.getOrPut(9) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal fun createOutputMockSlot(slot: Int): PluginBase {
    return when (slot) {
        0 -> OutputMockSlot0()
        1 -> OutputMockSlot1()
        2 -> OutputMockSlot2()
        3 -> OutputMockSlot3()
        4 -> OutputMockSlot4()
        5 -> OutputMockSlot5()
        6 -> OutputMockSlot6()
        7 -> OutputMockSlot7()
        8 -> OutputMockSlot8()
        9 -> OutputMockSlot9()
        else -> throw IllegalArgumentException("Invalid mock slot: $slot")
    }
}
