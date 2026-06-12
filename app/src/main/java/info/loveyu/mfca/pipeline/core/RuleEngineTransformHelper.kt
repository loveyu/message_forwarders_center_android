package info.loveyu.mfca.pipeline.core

import info.loveyu.mfca.config.models.TransformConfig
import info.loveyu.mfca.pipeline.enrich.Enricher
import info.loveyu.mfca.pipeline.expression.*
import info.loveyu.mfca.input.InputMessage
import info.loveyu.mfca.util.LogManager
import org.json.JSONObject

internal fun applyTransform(
    transform: TransformConfig,
    data: ByteArray,
    preParsedJson: JSONObject?,
    inputMessage: InputMessage,
    expressionEngine: ExpressionEngine,
    ruleName: String = ""
): ByteArray? {
    var currentData = data

    val json = preParsedJson ?: try {
        JSONObject(String(data))
    } catch (_: Exception) {
        null
    }

    if (transform.extract != null) {
        val context = buildRuleContext(ruleName, inputMessage)
        val extracted = expressionEngine.evaluateExtractExpression(
            data, json, transform.extract, inputMessage.headers, context
        )
        if (extracted != null) {
            currentData = extracted
        } else {
            return null
        }
    }

    transform.formatSteps?.let { steps ->
        val context =
            mapOf(
                "rule" to ruleName,
                "source" to inputMessage.source,
                "timestamp" to (System.currentTimeMillis() / 1000).toString(),
                "unix" to System.currentTimeMillis().toString(),
                "receivedAt" to (inputMessage.headers["X-ReceivedAt"] ?: System.currentTimeMillis().toString())
            )
        val (newData, _) =
            expressionEngine.applyFormatSteps(steps, currentData, inputMessage.headers, context)
        return newData
    }
    transform.format?.let { template ->
        val context =
            mapOf(
                "rule" to ruleName,
                "source" to inputMessage.source,
                "timestamp" to (System.currentTimeMillis() / 1000).toString(),
                "unix" to System.currentTimeMillis().toString(),
                "receivedAt" to (inputMessage.headers["X-ReceivedAt"] ?: System.currentTimeMillis().toString())
            )
        return expressionEngine.evaluateFormatTemplate(template, currentData, json, inputMessage.headers, context)
    }

    return currentData
}

internal suspend fun applyEnrich(
    enrichSpec: String,
    data: ByteArray,
    preParsedJson: JSONObject?,
    enrichers: java.util.concurrent.ConcurrentHashMap<String, Enricher>
): ByteArray? {
    val colonIndex = enrichSpec.indexOf(':')
    if (colonIndex == -1) {
        LogManager.logWarn("RULE", "Invalid enrich spec: $enrichSpec (expected type:parameter)")
        return null
    }
    val type = enrichSpec.substring(0, colonIndex)
    val parameter = enrichSpec.substring(colonIndex + 1)

    val enricher = enrichers[type]
    if (enricher == null) {
        LogManager.logWarn("RULE", "Unknown enricher type: $type")
        return null
    }

    val json = preParsedJson ?: try {
        JSONObject(String(data))
    } catch (e: Exception) {
        LogManager.logWarn("RULE", "Enrich requires JSON data, got non-JSON")
        return null
    }

    val enriched = enricher.enrich(json, parameter) ?: return null
    return enriched.toString().toByteArray()
}
