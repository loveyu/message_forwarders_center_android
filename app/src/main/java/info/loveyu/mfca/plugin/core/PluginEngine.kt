package info.loveyu.mfca.plugin.core

import android.content.Context
import info.loveyu.mfca.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class PluginEngine(
    private val context: Context,
    private val prefix: String,
    private val slotFactory: (Int) -> PluginBase,
) {
    companion object {
        const val MAX_SLOTS = 10
    }

    private val plugins = arrayOfNulls<PluginBase?>(MAX_SLOTS)
    private val slotCaps = IntArray(MAX_SLOTS)
    val slotStats = Array(MAX_SLOTS) { SlotStats() }

    private val logScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingLogs = mutableListOf<Pair<Int, PluginLogEntry>>()
    private val logLock = Any()

    fun getInstalledPath(slot: Int): File {
        val abi = info.loveyu.mfca.plugin.PluginManager.deviceAbi
        return File(context.filesDir, "plugins/${prefix}_plugin_slot_$slot/$abi")
            .resolve("lib${prefix}_plugin_slot_$slot.so")
    }

    fun loadPlugin(slot: Int): Boolean {
        val soFile = getInstalledPath(slot)
        if (!soFile.exists()) return false
        if (plugins[slot] != null) return true
        return try {
            val plugin = slotFactory(slot)
            plugin.load(soFile.absolutePath)
            slotCaps[slot] = plugin.getCapabilities()
            plugins[slot] = plugin
            LogManager.logDebug("$prefix.Plugin", "Loaded slot $slot: caps=${slotCaps[slot]}")
            true
        } catch (e: Exception) {
            LogManager.logError("$prefix.Plugin", "Failed to load slot $slot: ${e.message}")
            false
        }
    }

    fun loadConfiguredSlots(slots: List<Int>) {
        slots.distinct().sorted().forEach { loadPlugin(it) }
    }

    fun processSerial(
        inputJsonBuilder: (Int) -> String,
        slots: List<Int>,
    ): PluginAppliedResult? {
        var result: PluginAppliedResult? = null
        for (slot in slots.sorted()) {
            val plugin = plugins[slot] ?: continue
            if (slotCaps[slot] and PluginBase.CAP_FRONT == 0) {
                LogManager.logWarn("$prefix.Plugin", "Slot $slot CAP_FRONT not set")
                continue
            }
            val inputJson = inputJsonBuilder(slot)
            val output = processSlot(slot, inputJson) ?: continue
            collectLogs(slot, output)
            when (parseAction(output)) {
                PluginAction.MODIFY -> {
                    result = applyResult(result, output)
                }
                PluginAction.PASS -> {}
            }
        }
        flushLogs()
        return result
    }

    fun processParallel(
        baseJson: String,
        slots: List<Int>,
    ): PluginAppliedResult? {
        var result: PluginAppliedResult? = null
        for (slot in slots.sorted()) {
            val plugin = plugins[slot] ?: continue
            if (slotCaps[slot] and PluginBase.CAP_FRONT == 0) {
                LogManager.logWarn("$prefix.Plugin", "Slot $slot CAP_FRONT not set")
                continue
            }
            val output = processSlot(slot, baseJson) ?: continue
            collectLogs(slot, output)
            when (parseAction(output)) {
                PluginAction.MODIFY -> {
                    result = applyResult(null, output)
                }
                PluginAction.PASS -> {}
            }
        }
        flushLogs()
        return result
    }

    fun processRearSerial(
        inputJsonBuilder: (Int) -> String,
        slots: List<Int>,
    ): PluginAppliedResult? {
        var result: PluginAppliedResult? = null
        for (slot in slots.sorted()) {
            val plugin = plugins[slot] ?: continue
            if (slotCaps[slot] and PluginBase.CAP_REAR == 0) {
                LogManager.logWarn("$prefix.Plugin", "Slot $slot CAP_REAR not set")
                continue
            }
            val inputJson = inputJsonBuilder(slot)
            val output = processSlot(slot, inputJson) ?: continue
            collectLogs(slot, output)
            when (parseAction(output)) {
                PluginAction.MODIFY -> {
                    result = applyResult(result, output)
                }
                PluginAction.PASS -> {}
            }
        }
        flushLogs()
        return result
    }

    fun processRearParallel(
        baseJson: String,
        slots: List<Int>,
    ): PluginAppliedResult? {
        var result: PluginAppliedResult? = null
        for (slot in slots.sorted()) {
            val plugin = plugins[slot] ?: continue
            if (slotCaps[slot] and PluginBase.CAP_REAR == 0) {
                LogManager.logWarn("$prefix.Plugin", "Slot $slot CAP_REAR not set")
                continue
            }
            val output = processSlot(slot, baseJson) ?: continue
            collectLogs(slot, output)
            when (parseAction(output)) {
                PluginAction.MODIFY -> {
                    result = applyResult(null, output)
                }
                PluginAction.PASS -> {}
            }
        }
        flushLogs()
        return result
    }

    fun isLoaded(slot: Int): Boolean = plugins[slot] != null

    fun unloadAll() {
        for (i in 0 until MAX_SLOTS) {
            plugins[i] = null
            slotCaps[i] = 0
            slotStats[i] = SlotStats()
        }
        synchronized(logLock) { pendingLogs.clear() }
    }

    // ── internal ──

    private fun processSlot(slot: Int, json: String): String? {
        val st = slotStats[slot]
        st.callCount++
        val t0 = System.currentTimeMillis()
        val result = runCatching { plugins[slot]?.process(json) }
            .onFailure { e ->
                st.errorCount++
                LogManager.logWarn("$prefix.Plugin", "Slot $slot error: ${e.message}")
            }
            .getOrNull()
        st.totalTimeMs += System.currentTimeMillis() - t0
        return result
    }

    private fun collectLogs(slot: Int, output: String) {
        val entries = extractLogs(output) ?: return
        synchronized(logLock) {
            pendingLogs.addAll(entries.map { slot to it })
        }
    }

    private fun flushLogs() {
        val batch: List<Pair<Int, PluginLogEntry>>
        synchronized(logLock) {
            if (pendingLogs.isEmpty()) return
            batch = pendingLogs.toList()
            pendingLogs.clear()
        }
        logScope.launch {
            batch.forEach { (slot, entry) ->
                val tag = "$prefix.Plugin.$slot"
                when (entry.level.lowercase()) {
                    "error" -> LogManager.logError(tag, entry.message)
                    "warn" -> LogManager.logWarn(tag, entry.message)
                    "info" -> LogManager.logInfo(tag, entry.message)
                    else -> LogManager.logDebug(tag, entry.message)
                }
            }
        }
    }

    private fun parseAction(output: String): PluginAction {
        return try {
            val obj = JSONObject(output)
            when (obj.optString("action", "modify")) {
                "pass" -> PluginAction.PASS
                else -> PluginAction.MODIFY
            }
        } catch (_: Exception) {
            PluginAction.MODIFY
        }
    }

    private fun extractLogs(output: String): List<PluginLogEntry>? {
        return try {
            val obj = JSONObject(output)
            val arr = obj.optJSONArray("logs") ?: return null
            if (arr.length() == 0) return null
            (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                PluginLogEntry(
                    level = e.optString("level", "info"),
                    message = e.optString("message", ""),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun applyResult(
        current: PluginAppliedResult?,
        output: String,
    ): PluginAppliedResult? {
        return try {
            val obj = JSONObject(output)
            PluginAppliedResult(
                dataBase64 = obj.optString("dataBase64", null)?.takeIf { it.isNotEmpty() },
                headers = obj.optJSONObject("headers")?.let { h ->
                    (current?.headers ?: emptyMap()) + h.keys().asSequence().associateWith { h.optString(it) }
                },
                metadata = obj.optJSONObject("metadata")?.let { m ->
                    (current?.metadata ?: emptyMap()) + m.keys().asSequence().associateWith { m.optString(it) }
                },
                statusCode = obj.optInt("statusCode", -1).takeIf { it > 0 },
                responseBody = obj.optString("responseBody", null)?.takeIf { it.isNotEmpty() },
                responseHeaders = obj.optJSONObject("responseHeaders")?.let { rh ->
                    (current?.responseHeaders ?: emptyMap()) + rh.keys().asSequence().associateWith { rh.optString(it) }
                },
            )
        } catch (_: Exception) {
            current
        }
    }
}
