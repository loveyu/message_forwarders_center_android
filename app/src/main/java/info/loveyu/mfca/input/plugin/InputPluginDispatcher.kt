package info.loveyu.mfca.input.plugin

import android.util.Base64
import info.loveyu.mfca.config.models.InputPluginConfig
import info.loveyu.mfca.config.models.PluginMode
import info.loveyu.mfca.input.InputMessage
import info.loveyu.mfca.plugin.core.PluginEngine
import org.json.JSONObject

class InputPluginDispatcher(
    private val engine: PluginEngine,
    private val config: InputPluginConfig?,
) {
    fun interceptFront(message: InputMessage): InputMessage {
        if (config == null || !config.enabled) return message
        val slots = config.front.slots
        if (slots.isEmpty()) return message

        val baseJson = buildFrontInput(message, null)
        val result = when (config.front.mode) {
            PluginMode.PARALLEL -> engine.processParallel(baseJson.toString(), slots)
            else -> engine.processSerial({ buildFrontInput(message, it) }, slots)
        }

        return if (result != null) applyFrontResult(message, result) else message
    }

    fun interceptRear(
        sourceName: String,
        uri: String,
        method: String,
        statusCode: Int,
        responseBody: String,
        responseHeaders: Map<String, String>,
    ): PluginAppliedHttpResult? {
        if (config == null || !config.enabled) return null
        val slots = config.rear.slots
        if (slots.isEmpty()) return null

        val baseJson = buildRearInput(sourceName, uri, method, statusCode, responseBody, responseHeaders)
        val result = when (config.rear.mode) {
            PluginMode.PARALLEL -> engine.processRearParallel(baseJson.toString(), slots)
            else -> engine.processRearSerial(
                { buildRearInput(sourceName, uri, method, statusCode, responseBody, responseHeaders) },
                slots,
            )
        }

        return if (result != null) {
            PluginAppliedHttpResult(
                statusCode = result.statusCode ?: statusCode,
                responseBody = result.responseBody ?: responseBody,
                responseHeaders = result.responseHeaders ?: responseHeaders,
            )
        } else null
    }

    data class PluginAppliedHttpResult(
        val statusCode: Int,
        val responseBody: String,
        val responseHeaders: Map<String, String>,
    )

    private fun buildFrontInput(message: InputMessage, slot: Int?): String {
        return JSONObject().apply {
            put("version", 1)
            put("type", "input_front")
            put("source", message.source)
            put("dataBase64", Base64.encodeToString(message.data, Base64.NO_WRAP))
            put("headers", JSONObject(message.headers))
            put("metadata", JSONObject(message.metadata))
        }.toString()
    }

    private fun buildRearInput(
        sourceName: String,
        uri: String,
        method: String,
        statusCode: Int,
        responseBody: String,
        responseHeaders: Map<String, String>,
    ): String {
        return JSONObject().apply {
            put("version", 1)
            put("type", "input_rear")
            put("source", sourceName)
            put("method", method)
            put("uri", uri)
            put("statusCode", statusCode)
            put("responseBody", responseBody)
            put("responseHeaders", JSONObject(responseHeaders))
        }.toString()
    }

    private fun applyFrontResult(message: InputMessage, result: info.loveyu.mfca.plugin.core.PluginAppliedResult): InputMessage {
        var data = message.data
        var headers = message.headers
        var metadata = message.metadata

        result.dataBase64?.let { b64 ->
            data = Base64.decode(b64, Base64.DEFAULT)
        }
        result.headers?.let { h ->
            headers = headers + h
        }
        result.metadata?.let { m ->
            metadata = metadata + m
        }
        return message.copy(data = data, headers = headers, metadata = metadata)
    }
}
