package info.loveyu.mfca.output.plugin

import android.util.Base64
import info.loveyu.mfca.config.models.OutputPluginConfig
import info.loveyu.mfca.config.models.PluginMode
import info.loveyu.mfca.plugin.core.PluginEngine
import org.json.JSONObject

class OutputPluginDispatcher(
    private val engine: PluginEngine,
    private val config: OutputPluginConfig?,
) {
    fun intercept(
        outputName: String,
        outputTypeName: String,
        data: ByteArray,
        headers: Map<String, String>,
        ruleName: String,
        source: String,
    ): Pair<ByteArray, Map<String, String>> {
        if (config == null || !config.enabled) return data to headers
        val slots = config.front.slots
        if (slots.isEmpty()) return data to headers

        val baseJson = buildOutputInput(outputName, outputTypeName, data, headers, ruleName, source)
        val result = when (config.front.mode) {
            PluginMode.PARALLEL -> engine.processParallel(baseJson.toString(), slots)
            else -> engine.processSerial(
                { buildOutputInput(outputName, outputTypeName, data, headers, ruleName, source) },
                slots,
            )
        }

        return if (result?.dataBase64 != null) {
            Base64.decode(result.dataBase64, Base64.DEFAULT) to
                (headers + (result.headers ?: emptyMap()))
        } else data to headers
    }

    private fun buildOutputInput(
        outputName: String,
        outputTypeName: String,
        data: ByteArray,
        headers: Map<String, String>,
        ruleName: String,
        source: String,
    ): String {
        return JSONObject().apply {
            put("version", 1)
            put("type", "output")
            put("source", outputName)
            put("outputType", outputTypeName)
            put("dataBase64", Base64.encodeToString(data, Base64.NO_WRAP))
            put("headers", JSONObject(headers))
            put("metadata", JSONObject().apply {
                put("rule", ruleName)
                put("source", source)
                put("outputName", outputName)
            })
        }.toString()
    }
}
