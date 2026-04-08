package info.loveyu.mfca.pipeline

import info.loveyu.mfca.config.AppConfig
import info.loveyu.mfca.config.RuleConfig
import info.loveyu.mfca.input.InputMessage
import info.loveyu.mfca.output.OutputManager
import info.loveyu.mfca.queue.QueueItem
import info.loveyu.mfca.util.LogManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 规则引擎
 */
class RuleEngine(
    private val config: AppConfig
) {
    private val rules = mutableMapOf<String, RuleConfig>()
    private val inputRulesMap = ConcurrentHashMap<String, MutableList<RuleConfig>>()

    init {
        config.rules.forEach { rule ->
            rules[rule.name] = rule
            inputRulesMap.getOrPut(rule.from) { mutableListOf() }.add(rule)
        }
    }

    /**
     * 处理输入消息
     */
    fun process(inputMessage: InputMessage) {
        val matchingRules = inputRulesMap[inputMessage.source] ?: return

        matchingRules.forEach { rule ->
            try {
                processRule(rule, inputMessage)
            } catch (e: Exception) {
                LogManager.appendLog("RULE", "Error processing rule ${rule.name}: ${e.message}")
                handleError(rule, inputMessage, e)
            }
        }
    }

    private fun processRule(rule: RuleConfig, inputMessage: InputMessage) {
        LogManager.appendLog("RULE", "Processing rule: ${rule.name}")

        var currentData = inputMessage.data

        for (step in rule.pipeline) {
            val transform = step.transform
            var skipStep = false

            // Apply detect first if specified
            if (transform?.detect != null) {
                if (!detectMedia(currentData, transform.detect)) {
                    LogManager.appendLog("RULE", "Detect failed for ${transform.detect}")
                    skipStep = true
                }
            }

            // Apply transform if present
            if (!skipStep && transform != null) {
                val transformed = applyTransform(transform, currentData, inputMessage)
                if (transformed == null) {
                    LogManager.appendLog("RULE", "Transform returned null, skipping")
                    skipStep = true
                } else {
                    currentData = transformed
                }
            }

            // Apply filter if present
            if (!skipStep && transform?.filter != null) {
                if (!evaluateFilter(transform.filter, currentData)) {
                    LogManager.appendLog("RULE", "Filter rejected message")
                    skipStep = true
                }
            }

            if (skipStep) {
                continue
            }

            // Send to outputs
            step.to.forEach { outputName ->
                val output = OutputManager.getOutput(outputName)
                if (output != null) {
                    val item = QueueItem(
                        data = currentData,
                        metadata = mapOf("rule" to rule.name)
                    )
                    output.send(item) { success ->
                        LogManager.appendLog("RULE", "Output $outputName: ${if (success) "OK" else "FAILED"}")
                    }
                } else {
                    LogManager.appendLog("RULE", "Output not found: $outputName")
                }
            }
        }
    }

    private fun applyTransform(
        transform: info.loveyu.mfca.config.TransformConfig,
        data: ByteArray,
        inputMessage: InputMessage
    ): ByteArray? {
        val json = try {
            JSONObject(String(data))
        } catch (e: Exception) {
            // Not JSON, return raw data if no extract specified
            if (transform.extract == null) {
                return data
            }
            return null
        }

        // Extract
        transform.extract?.let { path ->
            val extracted = extractFromJson(json, path)
            if (extracted != null) {
                return when (extracted) {
                    is String -> extracted.toByteArray()
                    is Number -> extracted.toString().toByteArray()
                    is Boolean -> extracted.toString().toByteArray()
                    is JSONArray -> extracted.toString().toByteArray()
                    is JSONObject -> extracted.toString().toByteArray()
                    else -> extracted.toString().toByteArray()
                }
            }
        }

        return data
    }

    private fun extractFromJson(json: JSONObject, path: String): Any? {
        // Handle $raw special case
        if (path == "\$raw") {
            return json.toString()
        }

        // Handle array access like "data.list[0]"
        val arrayMatch = Regex("(.*)\\[(\\d+)\\]").find(path)
        if (arrayMatch != null) {
            val objPath = arrayMatch.groupValues[1]
            val index = arrayMatch.groupValues[2].toIntOrNull() ?: return null

            val obj = if (objPath.isEmpty()) json else navigatePath(json, objPath)
            if (obj is JSONArray) {
                return obj.opt(index)
            }
            return null
        }

        return navigatePath(json, path)
    }

    private fun navigatePath(json: JSONObject, path: String): Any? {
        val parts = path.split(".")
        var current: Any? = json
        for (part in parts) {
            if (part.isEmpty()) continue
            current = when (current) {
                is JSONObject -> (current as JSONObject).opt(part)
                is JSONArray -> (current as JSONArray).opt(part.toIntOrNull() ?: 0)
                else -> null
            }
            if (current == null) break
        }
        return current
    }

    private fun evaluateFilter(filter: String, data: ByteArray): Boolean {
        // Try to parse as JSON
        val json = try {
            JSONObject(String(data))
        } catch (e: Exception) {
            // If not JSON, just return true
            return true
        }

        return evaluateJsonFilter(filter, json)
    }

    private fun evaluateJsonFilter(filter: String, json: JSONObject): Boolean {
        // Simple expression parser
        // Supports: len(path) > N, path == value, path != value, path > N, path < N

        try {
            // len() function
            val lenMatch = Regex("""len\(([^)]+)\)\s*(\S+)\s*(\d+)""").find(filter)
            if (lenMatch != null) {
                val path = lenMatch.groupValues[1]
                val op = lenMatch.groupValues[2]
                val value = lenMatch.groupValues[3].toIntOrNull() ?: return true

                val extracted = extractFromJson(json, path)
                val length = when (extracted) {
                    is JSONArray -> extracted.length()
                    is String -> extracted.length
                    is Collection<*> -> extracted.size
                    else -> return true
                }

                return when (op) {
                    ">" -> length > value
                    ">=" -> length >= value
                    "<" -> length < value
                    "<=" -> length <= value
                    "==" -> length == value
                    "!=" -> length != value
                    else -> true
                }
            }

            // Comparison operators: path op value
            val compMatch = Regex("""([^!=<>\s]+)\s*(!=|==|>=|<=|>|<)\s*(.+)""").find(filter)
            if (compMatch != null) {
                val path = compMatch.groupValues[1].trim()
                val op = compMatch.groupValues[2]
                val value = compMatch.groupValues[3].trim().removeSurrounding("\"")

                val extracted = extractFromJson(json, path)
                val extractedStr = extracted?.toString() ?: ""

                return when (op) {
                    "==" -> extractedStr == value
                    "!=" -> extractedStr != value
                    ">" -> (extracted as? Number)?.toDouble()?.let { it > value.toDouble() } ?: false
                    "<" -> (extracted as? Number)?.toDouble()?.let { it < value.toDouble() } ?: false
                    ">=" -> (extracted as? Number)?.toDouble()?.let { it >= value.toDouble() } ?: false
                    "<=" -> (extracted as? Number)?.toDouble()?.let { it <= value.toDouble() } ?: false
                    else -> true
                }
            }

            // Equality check without operator
            val eqMatch = Regex("""([^=\s]+)\s*==\s*(.+)""").find(filter)
            if (eqMatch != null) {
                val path = eqMatch.groupValues[1].trim()
                val value = eqMatch.groupValues[2].trim().removeSurrounding("\"")
                val extracted = extractFromJson(json, path)
                return extracted?.toString() == value
            }
        } catch (e: Exception) {
            LogManager.appendLog("RULE", "Filter evaluation error: ${e.message}")
        }

        return true // Default to pass
    }

    private fun detectMedia(data: ByteArray, type: String): Boolean {
        return when (type.lowercase()) {
            "image" -> isImage(data)
            "json" -> isJson(data)
            "text" -> isText(data)
            else -> true
        }
    }

    private fun isImage(data: ByteArray): Boolean {
        if (data.size < 4) return false

        // PNG signature
        if (data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()) {
            return true
        }

        // JPEG signature
        if (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) {
            return true
        }

        // GIF signature
        if (data[0] == 0x47.toByte() && data[1] == 0x49.toByte() &&
            data[2] == 0x46.toByte()) {
            return true
        }

        // BMP signature
        if (data[0] == 0x42.toByte() && data[1] == 0x4D.toByte()) {
            return true
        }

        // WebP signature
        if (data.size >= 12 &&
            data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
            data[2] == 0x46.toByte() && data[3] == 0x46.toByte() &&
            data[8] == 0x57.toByte() && data[9] == 0x45.toByte() &&
            data[10] == 0x42.toByte() && data[11] == 0x50.toByte()) {
            return true
        }

        return false
    }

    private fun isJson(data: ByteArray): Boolean {
        try {
            val str = String(data.take(100).toByteArray()).trim()
            return str.startsWith("{") || str.startsWith("[")
        } catch (e: Exception) {
            return false
        }
    }

    private fun isText(data: ByteArray): Boolean {
        if (data.isEmpty()) return true
        // Check if data is mostly printable ASCII
        var printable = 0
        for (b in data.take(100)) {
            val byte = b.toInt() and 0xFF
            if (byte in 32..126 || byte in 9..10 || byte == 13) {
                printable++
            }
        }
        return printable > (data.size.coerceAtMost(100)) * 0.85
    }

    private fun handleError(rule: RuleConfig, inputMessage: InputMessage, error: Exception) {
        rule.onError?.forEach { step ->
            step.to.forEach { outputName ->
                val output = OutputManager.getOutput(outputName)
                if (output != null) {
                    val item = QueueItem(
                        data = inputMessage.data,
                        metadata = mapOf(
                            "rule" to rule.name,
                            "error" to (error.message ?: "Unknown error")
                        )
                    )
                    output.send(item, null)
                }
            }
        }
    }

    fun getRulesForInput(inputName: String): List<RuleConfig> {
        return inputRulesMap[inputName] ?: emptyList()
    }

    fun getAllRules(): Map<String, RuleConfig> = rules.toMap()
}
