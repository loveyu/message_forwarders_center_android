package info.loveyu.mfca.test.input_plugin

import android.util.Base64
import info.loveyu.mfca.plugin.core.PluginBase
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal val inputMockRecords: MutableMap<Int, MutableList<Pair<String, String?>>> = ConcurrentHashMap()

internal fun clearInputMockRecords() {
    inputMockRecords.clear()
}

internal fun getInputMockRecords(slot: Int): List<Pair<String, String?>> {
    return inputMockRecords[slot]?.toList() ?: emptyList()
}

internal fun getInputMockStats(slot: Int): MockSlotStats {
    val records = inputMockRecords[slot]
    if (records == null || records.isEmpty()) return MockSlotStats()
    val last = records.last()
    return MockSlotStats(
        callCount = records.size,
        lastInput = last.first,
        lastOutput = last.second,
    )
}

internal data class MockSlotStats(
    val callCount: Int = 0,
    val lastInput: String = "",
    val lastOutput: String? = null,
)

internal class InputMockSlot0 : PluginBase("input_test_slot_0") {
    override fun process(inputJson: String): String? {
        val output = JSONObject().apply {
            put("action", "pass")
            put("logs", JSONObject.wrap(listOf(
                JSONObject().apply { put("level", "info"); put("message", "Slot0: pass-through") }
            )))
        }.toString()
        inputMockRecords.getOrPut(0) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT or CAP_REAR
}

internal class InputMockSlot1 : PluginBase("input_test_slot_1") {
    override fun process(inputJson: String): String? {
        val input = JSONObject(inputJson)
        val b64 = input.optString("dataBase64", "")
        val decoded = if (b64.isNotBlank()) String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) else ""
        val modified = "[MODIFIED_BY_SLOT1] $decoded"
        val encoded = Base64.encodeToString(modified.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val output = JSONObject().apply {
            put("action", "modify")
            put("dataBase64", encoded)
            put("logs", JSONObject.wrap(listOf(
                JSONObject().apply { put("level", "info"); put("message", "Slot1: prepended prefix to data") }
            )))
        }.toString()
        inputMockRecords.getOrPut(1) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal class InputMockSlot2 : PluginBase("input_test_slot_2") {
    override fun process(inputJson: String): String? {
        val input = JSONObject(inputJson)
        val type = input.optString("type", "")
        val output: String
        if (type != "input_rear") {
            output = JSONObject().apply {
                put("action", "pass")
                put("logs", JSONObject.wrap(listOf(
                    JSONObject().apply { put("level", "warn"); put("message", "Slot2: rear-only, skipped") }
                )))
            }.toString()
        } else {
            output = JSONObject().apply {
                put("action", "modify")
                put("responseHeaders", JSONObject().apply { put("x-processed-by", "slot2") })
                put("logs", JSONObject.wrap(listOf(
                    JSONObject().apply { put("level", "info"); put("message", "Slot2: added response header") }
                )))
            }.toString()
        }
        inputMockRecords.getOrPut(2) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_REAR
}

internal class InputMockSlot3 : PluginBase("input_test_slot_3") {
    override fun process(inputJson: String): String? {
        inputMockRecords.getOrPut(3) { mutableListOf() }.add(inputJson to null)
        return null
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal class InputMockSlot4 : PluginBase("input_test_slot_4") {
    override fun process(inputJson: String): String? {
        val input = JSONObject(inputJson)
        val b64 = input.optString("dataBase64", "")
        val decoded = if (b64.isNotBlank()) String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) else ""
        val modified = "[SLOT4] $decoded"
        val encoded = Base64.encodeToString(modified.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val output = JSONObject().apply {
            put("action", "modify")
            put("dataBase64", encoded)
            put("headers", JSONObject().apply {
                put("x-slot4", "applied")
                put("x-timestamp", System.currentTimeMillis().toString())
            })
            put("logs", JSONObject.wrap(listOf(
                JSONObject().apply { put("level", "info"); put("message", "Slot4: modified data + headers") }
            )))
        }.toString()
        inputMockRecords.getOrPut(4) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal class InputMockSlot5 : PluginBase("input_test_slot_5") {
    override fun process(inputJson: String): String? {
        val input = JSONObject(inputJson)
        val type = input.optString("type", "")
        val output: String
        if (type != "input_rear") {
            output = JSONObject().apply {
                put("action", "pass")
                put("logs", JSONObject.wrap(listOf(
                    JSONObject().apply { put("level", "warn"); put("message", "Slot5: rear-only") }
                )))
            }.toString()
        } else {
            val body = input.optString("responseBody", "")
            val modified = "<processed>$body</processed>"
            output = JSONObject().apply {
                put("action", "modify")
                put("responseBody", modified)
                put("logs", JSONObject.wrap(listOf(
                    JSONObject().apply { put("level", "info"); put("message", "Slot5: wrapped response body") }
                )))
            }.toString()
        }
        inputMockRecords.getOrPut(5) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_REAR
}

internal class InputMockSlot6 : PluginBase("input_test_slot_6") {
    override fun process(inputJson: String): String? {
        val input = JSONObject(inputJson)
        val type = input.optString("type", "")
        val b64 = input.optString("dataBase64", "")
        val decoded = if (b64.isNotBlank()) String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) else ""
        val modified: String
        val logMsg: String
        if (type == "input_front") {
            modified = decoded.uppercase()
            logMsg = "Slot6: front - uppercased"
        } else {
            modified = decoded.lowercase()
            logMsg = "Slot6: rear - lowercased"
        }
        val encoded = Base64.encodeToString(modified.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val output = JSONObject().apply {
            put("action", "modify")
            put("dataBase64", encoded)
            put("logs", JSONObject.wrap(listOf(
                JSONObject().apply { put("level", "info"); put("message", logMsg) }
            )))
        }.toString()
        inputMockRecords.getOrPut(6) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT or CAP_REAR
}

internal class InputMockSlot7 : PluginBase("input_test_slot_7") {
    override fun process(inputJson: String): String? {
        val output = JSONObject().apply {
            put("action", "pass")
            put("logs", JSONObject.wrap(listOf(
                JSONObject().apply { put("level", "warn"); put("message", "Slot7: warning log, pass-through") }
            )))
        }.toString()
        inputMockRecords.getOrPut(7) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal class InputMockSlot8 : PluginBase("input_test_slot_8") {
    override fun process(inputJson: String): String? {
        val output = JSONObject().apply {
            put("action", "modify")
            put("metadata", JSONObject().apply {
                put("slot8", "active")
                put("origin", "test")
                put("processedAt", System.currentTimeMillis().toString())
            })
            put("logs", JSONObject.wrap(listOf(
                JSONObject().apply { put("level", "info"); put("message", "Slot8: injected metadata") }
            )))
        }.toString()
        inputMockRecords.getOrPut(8) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal class InputMockSlot9 : PluginBase("input_test_slot_9") {
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
                JSONObject().apply { put("level", "info"); put("message", "Slot9: reversed data") },
                JSONObject().apply { put("level", "info"); put("message", "Slot9: len=$decoded.length") },
            )))
        }.toString()
        inputMockRecords.getOrPut(9) { mutableListOf() }.add(inputJson to output)
        return output
    }

    override fun getCapabilities(): Int = CAP_FRONT
}

internal fun createInputMockSlot(slot: Int): PluginBase {
    return when (slot) {
        0 -> InputMockSlot0()
        1 -> InputMockSlot1()
        2 -> InputMockSlot2()
        3 -> InputMockSlot3()
        4 -> InputMockSlot4()
        5 -> InputMockSlot5()
        6 -> InputMockSlot6()
        7 -> InputMockSlot7()
        8 -> InputMockSlot8()
        9 -> InputMockSlot9()
        else -> throw IllegalArgumentException("Invalid mock slot: $slot")
    }
}
